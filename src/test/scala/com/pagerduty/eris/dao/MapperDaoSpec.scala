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

import com.pagerduty.eris.serializers._
import com.pagerduty.eris.{ ColumnFamilySettings, ColumnModel, TimeUuid, TestClusterCtx }
import com.pagerduty.mapper.annotations._
import org.scalatest.{ Matchers, FreeSpec }
import scala.concurrent.ExecutionContextExecutor

package test {
  @Entity case class TestEntity(
      @Column(name = "f0") field0: String,
      @Column(name = "f1") field1: Int
  ) {
    def this() = this("default0", 0)
  }
}

class MapperDaoSpec extends FreeSpec with Matchers {

  "MapperDao should" - {

    "handle entityColumnFamily correctly" - {
      type Id = TimeUuid
      type Entity = test.TestEntity
      val keyspaceName = "MapperDaoSpec"
      val mainCfName = "mainCf"

      trait PartialDaoImpl extends MapperDao[Id, Entity] {
        val cluster = TestClusterCtx.cluster
        val keyspace = cluster.getKeyspace(keyspaceName)
        val entityClass = classOf[Entity]
      }

      "simple case" in {
        val dao = new PartialDaoImpl {
          protected def executor: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
          val mainFamily = entityColumnFamily(mainCfName)()
        }

        dao.columnFamilyDefs.size shouldBe 1
        dao.columnFamilyDefs.forall(_.getKeyspace == keyspaceName) shouldBe true

        val mainCfDef = dao.columnFamilyDefs.find(_.getName == mainCfName).get
        mainCfDef.getKeyValidationClass shouldBe ValidatorClass[Id]
        mainCfDef.getComparatorType shouldBe ValidatorClass[String]
        mainCfDef.getDefaultValidationClass shouldBe ValidatorClass[String]
        mainCfDef.getColumnDefinitionList.size shouldBe 1
        val f1Col = mainCfDef.getColumnDefinitionList.get(0)
        f1Col.getName shouldBe "f1"
        f1Col.getValidationClass shouldBe ValidatorClass[Int]
      }

      "with custom colValueValidator in settings" in {
        val dao = new PartialDaoImpl {
          protected def executor: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
          val mainFamily = entityColumnFamily(mainCfName, new ColumnFamilySettings(
            colValueValidatorOverride = Some(ValidatorClass[Int])
          ))()
        }

        dao.columnFamilyDefs.size shouldBe 1
        dao.columnFamilyDefs.forall(_.getKeyspace == keyspaceName) shouldBe true

        val mainCfDef = dao.columnFamilyDefs.find(_.getName == mainCfName).get
        mainCfDef.getKeyValidationClass shouldBe ValidatorClass[Id]
        mainCfDef.getComparatorType shouldBe ValidatorClass[String]
        mainCfDef.getDefaultValidationClass shouldBe ValidatorClass[Int]
        mainCfDef.getColumnDefinitionList.size shouldBe 1
        val f0Col = mainCfDef.getColumnDefinitionList.get(0)
        f0Col.getName shouldBe "f0"
        f0Col.getValidationClass shouldBe ValidatorClass[String]
      }

      "with user specified columns" in {
        val dao = new PartialDaoImpl {
          protected def executor: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
          val mainFamily = entityColumnFamily(mainCfName)(ColumnModel[BigInt]("f1"))
        }

        val mainCfDef = dao.columnFamilyDefs.find(_.getName == mainCfName).get
        val f1Col = mainCfDef.getColumnDefinitionList.get(0)
        f1Col.getName shouldBe "f1"
        f1Col.getValidationClass shouldBe ValidatorClass[BigInt]
      }

      "multiple calls" in {
        val anotherColFamilyName = "anotherCf"
        val dao = new PartialDaoImpl {
          protected def executor: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
          val mainFamily = entityColumnFamily(mainCfName)()
          entityColumnFamily(anotherColFamilyName)()
        }

        dao.columnFamilyDefs.size shouldBe 2
        dao.columnFamilyDefs.forall(_.getKeyspace == keyspaceName) shouldBe true
        dao.columnFamilyDefs.find(_.getName == mainCfName).isDefined shouldBe true
        dao.columnFamilyDefs.find(_.getName == anotherColFamilyName).isDefined shouldBe true
      }
    }
  }
}
