/*
 * Copyright 2018-2020 Radicalbit S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.radicalbit.nsdb.index

import java.nio.file.Paths

import com.typesafe.scalalogging.LazyLogging
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement.{AvgAggregation, CountAggregation, PrimaryAggregation, SumAggregation}
import io.radicalbit.nsdb.model.{Location, TimeRange}
import io.radicalbit.nsdb.statement.StatementParser._
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter
import org.apache.lucene.index._
import org.apache.lucene.search.{IndexSearcher, Query, Sort}
import org.apache.lucene.util.InfoStream

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class AllFacetIndexes(basePath: String,
                      db: String,
                      namespace: String,
                      location: Location,
                      override val indexStorageStrategy: StorageStrategy)
    extends LazyLogging
    with DirectorySupport {

  private val directory = getDirectory(Paths.get(basePath, db, namespace, "shards", s"${location.shardName}", "facet"))
  private val taxoDirectory = getDirectory(
    Paths.get(basePath, db, namespace, "shards", s"${location.shardName}", "facet", "taxo"))

  private val facetSumIndex            = new FacetSumIndex(directory, taxoDirectory)
  private val facetCountIndex          = new FacetCountIndex(directory, taxoDirectory)
  private val facetRangeIndex          = new FacetRangeIndex
  private val sumAndCountFacetCombiner = new SumAndCountFacetCombiner(facetSumIndex, facetCountIndex)

  private val facetIndexes: Set[FacetIndex] = Set(facetCountIndex, facetSumIndex)

  def executeSumFacet(query: Query,
                      groupField: String,
                      sort: Option[Sort],
                      limit: Option[Int],
                      groupFieldIndexType: IndexType[_],
                      valueIndexType: IndexType[_]): Seq[Bit] =
    facetSumIndex.result(query, groupField, sort, limit, groupFieldIndexType, valueIndexType)

  def executeCountFacet(query: Query,
                        groupField: String,
                        sort: Option[Sort],
                        limit: Option[Int],
                        indexType: IndexType[_],
                        valueIndexType: IndexType[_] = BIGINT()): Seq[Bit] =
    facetCountIndex.result(query, groupField, sort, limit, indexType, valueIndexType)

  def executeRangeFacet(searcher: IndexSearcher,
                        query: Query,
                        internalAggregation: InternalTemporalAggregation,
                        rangeFieldName: String,
                        valueFieldName: String,
                        valueFieldType: Option[IndexType[_]],
                        ranges: Seq[TimeRange]): Seq[Bit] =
    internalAggregation.aggregation match {
      case primaryAggregation: PrimaryAggregation =>
        facetRangeIndex.executeRangeFacet(
          searcher,
          query,
          primaryAggregation,
          rangeFieldName,
          valueFieldName,
          valueFieldType,
          ranges
        )
      case AvgAggregation(aggregationField) =>
        val sum = facetRangeIndex.executeRangeFacet(
          searcher,
          query,
          SumAggregation(aggregationField),
          rangeFieldName,
          valueFieldName,
          valueFieldType,
          ranges
        )

        val countMap = facetRangeIndex
          .executeRangeFacet(
            searcher,
            query,
            CountAggregation(aggregationField),
            rangeFieldName,
            valueFieldName,
            valueFieldType,
            ranges
          )
          .groupBy(_.timestamp)

        sum.map { sumBit =>
          countMap
            .get(sumBit.timestamp)
            .flatMap(_.headOption)
            .fold(sumBit)(countBit =>
              sumBit.copy(value = 0.0, tags = Map("count" -> countBit.value, "sum" -> sumBit.value)))
        }
    }

  def executeDistinctFieldCountIndex(query: Query, field: String, sort: Option[Sort]): Seq[Bit] =
    facetCountIndex.getDistinctField(query, field, sort)

  def executeSumAndCountFacet(query: Query,
                              groupField: String,
                              sort: Option[Sort],
                              indexType: IndexType[_],
                              valueIndexType: IndexType[_]): Seq[Bit] =
    sumAndCountFacetCombiner.executeSumAndCountFacet(query, groupField, sort, indexType, valueIndexType)

  /**
    * @return the [[org.apache.lucene.index.IndexWriter]]
    */
  def getIndexWriter: IndexWriter =
    new IndexWriter(
      directory,
      new IndexWriterConfig(new StandardAnalyzer)
        .setUseCompoundFile(true)
        .setMergedSegmentWarmer(new SimpleMergedSegmentWarmer(InfoStream.NO_OUTPUT))
    )

  /**
    * @return the [[org.apache.lucene.facet.taxonomy.TaxonomyWriter]]
    */
  def getTaxonomyWriter: DirectoryTaxonomyWriter = new DirectoryTaxonomyWriter(taxoDirectory)

  def write(bit: Bit)(implicit writer: IndexWriter, taxonomyWriter: DirectoryTaxonomyWriter): Try[Long] = {

    @tailrec
    def writeBit(facetIndexesUpdated: Set[FacetIndex], nextFacetIndexes: Set[FacetIndex], counter: Long)(
        implicit writer: IndexWriter,
        taxonomyWriter: DirectoryTaxonomyWriter): Try[Long] =
      if (nextFacetIndexes.isEmpty) Success(counter)
      else {
        val facetIndex = nextFacetIndexes.head
        facetIndex.write(bit) match {
          case Success(c) => writeBit(facetIndexesUpdated + facetIndex, nextFacetIndexes.tail, counter + c)
          case fail @ Failure(t) =>
            logger.error("error during write on facet indexes", t)

            // rollback the previous write
            facetIndexesUpdated.foreach(_.delete(bit))
            // return the failure
            fail
        }
      }

    writeBit(Set.empty, facetIndexes, 0)
  }

  def delete(data: Bit)(implicit writer: IndexWriter): Set[Try[Long]] =
    for {
      index <- facetIndexes
      res = index.delete(data)
    } yield res

  def delete(query: Query)(implicit writer: IndexWriter): Set[Try[Long]] =
    for {
      index <- facetIndexes
      res = index.delete(query)
    } yield res

  def deleteAll()(implicit writer: IndexWriter): Set[Try[Long]] =
    for {
      index <- facetIndexes
      res = index.deleteAll()
    } yield res

  def refresh(): Try[Unit] = {
    Try { facetIndexes.foreach(_.refresh()) }.recover { case _: IndexNotFoundException => () }
  }

  def close(): Unit = {
    directory.close()
    taxoDirectory.close()
  }
}
