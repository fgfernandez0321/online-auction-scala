package com.example.auction.item.impl

import java.util.UUID

import akka.Done
import akka.stream.Materializer
import com.datastax.driver.core._
import com.example.auction.item.api.ItemSummary
import com.example.auction.item.api
import com.example.auction.utils.PaginatedSequence
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import akka.persistence.cassandra.ListenableFutureConverter
import collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

private[impl] class ItemRepository(session: CassandraSession)(implicit ec: ExecutionContext, mat: Materializer) {

  var paginationStates: scala.collection.mutable.Map[Int, String] = scala.collection.mutable.Map.empty

  def getItemsForUser(creatorId: UUID, status: api.ItemStatus.Status, page: Int, pageSize: Int, fetchSize: Int): Future[PaginatedSequence[ItemSummary]] = {
    val offset = page * pageSize
    val limit = (page + 1) * pageSize
    for {
      count <- countItemsByCreatorInStatus(creatorId, status)
      items <- if (offset > count) Future.successful(Nil)
      else selectItemsByCreatorInStatusWithPaging(creatorId, status, page, fetchSize)
    } yield {
      PaginatedSequence(items, page, pageSize, count)
    }
  }

  private def countItemsByCreatorInStatus(creatorId: UUID, status: api.ItemStatus.Status) = {
    session.selectOne("""
      SELECT COUNT(*) FROM itemSummaryByCreatorAndStatus
      WHERE creatorId = ? AND status = ?
      ORDER BY status ASC, itemId DESC
    """, // ORDER BY status is required due to https://issues.apache.org/jira/browse/CASSANDRA-10271
      creatorId, status.toString).map {
      case Some(row) => row.getLong("count").toInt
      case None => 0
    }
  }

  private def selectItemsByCreatorInStatus(creatorId: UUID, status: api.ItemStatus.Status, offset: Int, limit: Int) = {
    session.selectAll("""
      SELECT * FROM itemSummaryByCreatorAndStatus
      WHERE creatorId = ? AND status = ?
      ORDER BY status ASC, itemId DESC
      LIMIT ?
    """, creatorId, status.toString, Integer.valueOf(limit)).map { rows =>
      rows.drop(offset)
       .map(convertItemSummary)
    }
  }

  /**
    * Motivation: https://discuss.lightbend.com/t/how-to-specify-pagination-for-select-query-read-side/870
    */
  private def selectItemsByCreatorInStatusWithPaging(creatorId: UUID,
                                                     status: api.ItemStatus.Status,
                                                     pageNumber: Int,
                                                     fetchSize: Int) = {
    val statement = new SimpleStatement(
      """
          SELECT * FROM itemSummaryByCreatorAndStatus
          WHERE creatorId = ? AND status = ?
          ORDER BY status ASC, itemId DESC
          """, creatorId, status.toString)

    statement.setFetchSize(fetchSize)

    session.underlying().flatMap(underlyingSession => {

      if (pageNumber > 0) {
        if (paginationStates.contains(pageNumber)) {
          paginationStates.get(pageNumber).map(state =>
            statement.setPagingState(PagingState.fromString(state)))
        }
      }

      underlyingSession.executeAsync(statement).asScala map (resultSet => {
        val pagingState = resultSet.getExecutionInfo.getPagingState

        // Check against null due to Java code in `getPagingState` function
        if (pagingState != null && !paginationStates.contains(pageNumber + 1))
          paginationStates += (pageNumber + 1 -> pagingState.toString)

        val iterator = resultSet.iterator().asScala
        iterator.take(fetchSize).map(convertItemSummary).toSeq
      })
    })
  }

  private def convertItemSummary(item: Row): ItemSummary = {
    ItemSummary(
      item.getUUID("itemId"),
      item.getString("title"),
      item.getString("currencyId"),
      item.getInt("reservePrice"),
      api.ItemStatus.withName(item.getString("status"))
    )
  }
}

private[impl] class ItemEventProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
  extends ReadSideProcessor[ItemEvent] {
  private var insertItemCreatorStatement: PreparedStatement = null
  private var insertItemSummaryByCreatorStatement: PreparedStatement = null
  private var updateItemSummaryStatusStatement: PreparedStatement = null

  def buildHandler = {
    readSide.builder[ItemEvent]("itemEventOffset")
      .setGlobalPrepare(createTables)
      .setPrepare(_ => prepareStatements())
      .setEventHandler[ItemCreated](e => insertItem(e.event.item))
      .setEventHandler[AuctionStarted](e => updateItemSummaryStatus(e.entityId, api.ItemStatus.Auction))
      .setEventHandler[AuctionFinished](e => updateItemSummaryStatus(e.entityId, api.ItemStatus.Completed))
      .build
  }

  def aggregateTags = ItemEvent.Tag.allTags

  private def createTables() = {
    for {
      _ <- session.executeCreateTable("""
        CREATE TABLE IF NOT EXISTS itemCreator (
          itemId timeuuid PRIMARY KEY,
          creatorId UUID
        )
      """)
      _ <- session.executeCreateTable("""
        CREATE TABLE IF NOT EXISTS itemSummaryByCreator (
          creatorId UUID,
          itemId timeuuid,
          title text,
          currencyId text,
          reservePrice int,
          status text,
          PRIMARY KEY (creatorId, itemId)
        ) WITH CLUSTERING ORDER BY (itemId DESC)
      """)
      _ <- session.executeCreateTable("""
        CREATE MATERIALIZED VIEW IF NOT EXISTS itemSummaryByCreatorAndStatus AS
          SELECT * FROM itemSummaryByCreator
          WHERE status IS NOT NULL AND itemId IS NOT NULL
          PRIMARY KEY (creatorId, status, itemId)
          WITH CLUSTERING ORDER BY (status ASC, itemId DESC)
      """)
    } yield Done
  }

  private def prepareStatements() = {
    for {
      insertItemCreator <- session.prepare("""
        INSERT INTO itemCreator(itemId, creatorId) VALUES (?, ?)
      """)
      insertItemSummary <- session.prepare("""
        INSERT INTO itemSummaryByCreator(
          creatorId,
          itemId,
          title,
          currencyId,
          reservePrice,
          status
        ) VALUES (?, ?, ?, ?, ?, ?)
      """)
      updateItemStatus <- session.prepare("""
        UPDATE itemSummaryByCreator SET status = ? WHERE creatorId = ? AND itemId = ?
      """)
    } yield {
      insertItemCreatorStatement = insertItemCreator
      insertItemSummaryByCreatorStatement = insertItemSummary
      updateItemSummaryStatusStatement = updateItemStatus
      Done
    }
  }

  private def insertItem(item: Item) = {
    Future.successful(List(
      insertItemCreator(item),
      insertItemSummaryByCreator(item)
    ))
  }

  private def insertItemCreator(item: Item) = {
    insertItemCreatorStatement.bind(item.id, item.creator)
  }

  private def insertItemSummaryByCreator(item: Item) = {
    insertItemSummaryByCreatorStatement.bind(
      item.creator,
      item.id,
      item.title,
      item.currencyId,
      Integer.valueOf(item.reservePrice),
      item.status.toString
    )
  }

  private def updateItemSummaryStatus(itemId: String, status: api.ItemStatus.Status) = {
    val itemUuid = UUID.fromString(itemId)
    selectItemCreator(itemUuid).map {
      case None => throw new IllegalStateException("No itemCreator found for itemId " + itemId)
      case Some(row) =>
        val creatorId = row.getUUID("creatorId")
        List(updateItemSummaryStatusStatement.bind(status.toString, creatorId, itemUuid))
    }
  }

  private def selectItemCreator(itemId: UUID) = {
    session.selectOne("SELECT * FROM itemCreator WHERE itemId = ?", itemId)
  }
}
