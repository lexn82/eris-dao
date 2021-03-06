/*
 * Copyright (c) 2015, PagerDuty
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.pagerduty.eris.dao

import com.netflix.astyanax.Serializer
import com.pagerduty.eris._
import com.pagerduty.eris.mapper.EntityMapper
import com.pagerduty.eris.serializers._
import com.pagerduty.metrics.Stopwatch
import FutureConversions._
import scala.collection.JavaConversions._
import scala.concurrent.Future
import com.pagerduty.eris.serializers.ValidatorClass

/**
 * MapperDao provides basic CRUD method for a target entity class.
 *
 * Example:
 * {{{
 * class MyDao(val cluster: Cluster) extends MapperDao[MyId, MyEntity] {
 *   val keyspace = cluster.getKeyspace("myKeyspace")
 *   val entityClass = classOf[MyEntity]
 *   val mainFamily = entityColumnFamily("myCf")()
 *
 *   def find(id: MyId) = mapperFind(id)
 *   def find(ids: Iterable[MyId], batchSize: Int) = mapperFind(ids, batchSize)
 *   def resolve(ids: Seq[MyId], batchSize: Int) = mapperResolve(ids, batchSize)
 *   def persist(id: MyId, entity: MyEntity) = mapperPersist(id, entity)
 *   def remove(id: MyId) = mapperRemove(id)
 * }
 * }}}
 */
trait MapperDao[Id, Entity] extends Dao {

  // Abstract members.
  /**
   * The target entity class
   * @return entity class
   */
  protected def entityClass: Class[Entity]

  /**
   * Main column family.
   */
  protected val mainFamily: ColumnFamilyModel[Id, String, Array[Byte]]

  // Defined members.
  /**
   * Entity mapper. Can be overridden to specify custom serializers.
   */
  protected lazy val entityMapper: EntityMapper[Id, Entity] = {
    new EntityMapper(entityClass, CommonSerializers)
  }

  /**
   * Shorthand method for defining the main column family. Key serializer
   * will be based on the Dao#Id type, and colName serializer will be StringSerializer.
   * By default, ColValue validator will be set to ValidatorClass[String], event though the
   * declared column value type is Array[Byte].
   */
  protected def entityColumnFamily(
    name: String, columnFamilySettings: ColumnFamilySettings = new ColumnFamilySettings
  )(columns: ColumnModel*)(
    implicit
    rowKeySerializer: Serializer[Id]
  ): ColumnFamilyModel[Id, String, Array[Byte]] = {
    val defaultValueValidatorClass = columnFamilySettings
      .colValueValidatorOverride.getOrElse(ValidatorClass[String])

    val reflectionCols = entityMapper.columns
      .filterNot(_.validationClass == defaultValueValidatorClass)

    val colsByName =
      reflectionCols.map(col => col.name -> col).toMap ++
        columns.map(col => col.name -> col).toMap // Override with user specified values.

    columnFamily[Id, String, Array[Byte]](
      name,
      columnFamilySettings.copy(colValueValidatorOverride = Some(defaultValueValidatorClass)),
      colsByName.values.toSet
    )
  }

  /**
   * Find entity with a given id.
   *
   * @param id entity id
   * @return Some(entity) if exists, None otherwise.
   */
  protected def mapperFind(id: Id): Future[Option[Entity]] = {
    val stopwatch = Stopwatch.start()
    val result = doMapperFind(id)
    val decorated = Interceptor.decorate(
      mainFamily.name, "entity_find_one", QueryType.Read, settings.metrics
    )(stopwatch, result)

    decorated
  }

  /**
   * Find a collection of entities with given ids, querying with maximum of `batchSize` number of
   * ids at a time.
   *
   * @param ids collection of ids
   * @param batchSize maximum number of ids to query at a time
   * @return a map of ids to entities
   */
  protected def mapperFind(ids: Iterable[Id], batchSize: Int = 100): Future[Map[Id, Entity]] = {
    val stopwatch = Stopwatch.start()
    val result = doMapperFind(ids, batchSize)
    val decorated = Interceptor.decorate(
      mainFamily.name, "entity_find_batch", QueryType.Read, settings.metrics
    )(stopwatch, result)

    decorated
  }

  /**
   * Resolves a sequence of ids into a sequence of entities, preserving the order and skipping
   * entities that cannot be found.
   *
   * @param ids a sequence of ids
   * @param batchSize maximum number of ids to query at a time
   * @return a sequence of found entities, in the same order as ids
   */
  protected def mapperResolve(ids: Seq[Id], batchSize: Int = 100): Future[Seq[Entity]] = {
    mapperFind(ids).map(res => ids.collect(res))
  }

  /**
   * Persist a given entity with a given id. Annotated @Id field is ignored.
   *
   * @param id target id
   * @param entity target entity
   * @return unit future
   */
  protected def mapperPersist(id: Id, entity: Entity): Future[Unit] = {
    val stopwatch = Stopwatch.start()
    val result = doMapperPersist(id, entity)
    val decorated = Interceptor.decorate(
      mainFamily.name, "entity_persist", QueryType.Write, settings.metrics
    )(stopwatch, result)

    decorated
  }

  /**
   * Removes entity with target id.
   *
   * @param id target id
   * @return unit future
   */
  protected def mapperRemove(id: Id): Future[Unit] = {
    val stopwatch = Stopwatch.start()
    val result = doMapperRemove(id)
    val decorated = Interceptor.decorate(
      mainFamily.name, "delete_row", QueryType.Write, settings.metrics
    )(stopwatch, result)

    decorated
  }

  private def doMapperFind(id: Id): Future[Option[Entity]] = {
    val query = keyspace.prepareQuery(mainFamily.columnFamily).getKey(id)
    query.executeAsync().map { res =>
      entityMapper.read(id, res.getResult)
    }
  }

  private def doMapperFind(ids: Iterable[Id], batchSize: Int = 100): Future[Map[Id, Entity]] = {
    val batches = ids.toSeq.grouped(batchSize)

    def query(idSeq: Seq[Id]): Future[Map[Id, Entity]] = {
      val query = keyspace.prepareQuery(mainFamily.columnFamily).getKeySlice(idSeq)
      query.executeAsync().map { res =>
        val loaded = for (row <- res.getResult) yield {
          row.getKey -> entityMapper.read(row.getKey, row.getColumns)
        }
        loaded.collect { case (id, Some(entity)) => id -> entity }.toMap
      }
    }

    val init = Future.successful(Map.empty[Id, Entity])
    batches.foldLeft(init) { (future, idsBatch) =>
      future.flatMap { accum => query(idsBatch).map(entities => accum ++ entities) }
    }
  }

  private def doMapperPersist(id: Id, entity: Entity): Future[Unit] = {
    val mutationBatch = keyspace.prepareMutationBatch()
    val rowMutation = mutationBatch.withRow(mainFamily.columnFamily, id)
    entityMapper.write(id, entity, rowMutation)
    mutationBatch.executeAsync().map { _ => Unit }
  }

  private def doMapperRemove(id: Id): Future[Unit] = {
    val mutationBatch = keyspace.prepareMutationBatch()
    mutationBatch.deleteRow(Seq(mainFamily.columnFamily), id)
    mutationBatch.executeAsync().map { _ => Unit }
  }
}
