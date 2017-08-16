package com.wavesplatform.history

import com.wavesplatform.TransactionGen
import com.wavesplatform.state2._
import com.wavesplatform.state2.diffs._
import org.scalacheck.{Gen, Shrink}
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import scorex.transaction._

class BlockchainUpdaterMicroblockSunnyDayTest extends PropSpec with PropertyChecks with DomainScenarioDrivenPropertyCheck with Matchers with TransactionGen {

  type Setup = (GenesisTransaction, PaymentTransaction, PaymentTransaction, PaymentTransaction)
  val preconditionsAndPayments: Gen[Setup] = for {
    master <- accountGen
    alice <- accountGen
    bob <- accountGen
    ts <- positiveIntGen
    fee <- smallFeeGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
    masterToAlice: PaymentTransaction <- paymentGeneratorP(master, alice)
    aliceToBob = PaymentTransaction.create(alice, bob, masterToAlice.amount - fee - 1, fee, ts).right.get
    aliceToBob2 = PaymentTransaction.create(alice, bob, masterToAlice.amount - fee - 1, fee, ts + 1).right.get
  } yield (genesis, masterToAlice, aliceToBob, aliceToBob2)

  property("all txs in different blocks: B0 <- B1 <- B2 <- B3!") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val blocks = chainBlocks(Seq(Seq(genesis), Seq(masterToAlice), Seq(aliceToBob), Seq(aliceToBob2)))
      blocks.init.foreach(block => domain.blockchainUpdater.processBlock(block).explicitGet())
      domain.blockchainUpdater.processBlock(blocks.last) should produce("unavailable funds")

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) shouldBe 0L
      domain.effBalance(aliceToBob.recipient) shouldBe 0L
    }
  }

  property("all txs in one block: B0 <- B0m1 <- B0m2 <- B0m3!") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, Seq(masterToAlice, aliceToBob, aliceToBob2).map(Seq(_)))
      domain.blockchainUpdater.processBlock(block).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(0)).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(1)).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(2)) should produce("unavailable funds")
      domain.history.lastBlock.get.transactionData shouldBe Seq(genesis, masterToAlice, aliceToBob)

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) > 0 shouldBe true
      domain.effBalance(aliceToBob.recipient) > 0 shouldBe true
    }
  }

  property("block references microBlock: B0 <- B1 <- B1m1 <- B2!") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, Seq(masterToAlice, aliceToBob, aliceToBob2).map(Seq(_)))
      domain.blockchainUpdater.processBlock(block).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(0)).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(1)).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks(2)) should produce("unavailable funds")

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) > 0 shouldBe true
      domain.effBalance(aliceToBob.recipient) > 0 shouldBe true
    }
  }

  property("discards some of microBlocks: B0 <- B0m1 <- B0m2; B0m1 <- B1") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val (block0, microBlocks0) = chainBaseAndMicro(randomSig, genesis, Seq(masterToAlice, aliceToBob).map(Seq(_)))
      val block1 = buildBlockOfTxs(microBlocks0.head.totalResBlockSig, Seq(aliceToBob2))
      domain.blockchainUpdater.processBlock(block0).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks0(0)).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks0(1)).explicitGet()
      domain.blockchainUpdater.processBlock(block1) shouldBe 'right

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) > 0 shouldBe true
      domain.effBalance(aliceToBob.recipient) shouldBe 0
    }
  }

  property("discards all microBlocks: B0 <- B1 <- B1m1; B1 <- B2") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val block0 = buildBlockOfTxs(randomSig, Seq(genesis))
      val (block1, microBlocks1) = chainBaseAndMicro(block0.uniqueId, masterToAlice, Seq(Seq(aliceToBob)))
      val block2 = buildBlockOfTxs(block1.uniqueId, Seq(aliceToBob2))
      domain.blockchainUpdater.processBlock(block0).explicitGet()
      domain.blockchainUpdater.processBlock(block1).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks1.head).explicitGet()
      domain.blockchainUpdater.processBlock(block2) shouldBe 'right

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) shouldBe 0
      domain.effBalance(aliceToBob.recipient) shouldBe 0
    }
  }

  property("discards liquid block completely: B0 <- B1 <- B1m1; B0 <- B2!") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, masterToAlice, aliceToBob, aliceToBob2)) =>
      val block0 = buildBlockOfTxs(randomSig, Seq(genesis))
      val (block1, microBlocks1) = chainBaseAndMicro(block0.uniqueId, masterToAlice, Seq(Seq(aliceToBob)))
      val block2 = buildBlockOfTxs(block0.uniqueId, Seq(aliceToBob2))
      domain.blockchainUpdater.processBlock(block0).explicitGet()
      domain.blockchainUpdater.processBlock(block1).explicitGet()
      domain.blockchainUpdater.processMicroBlock(microBlocks1(0)).explicitGet()
      domain.blockchainUpdater.processBlock(block2) should produce("References incorrect or non-existing block")

      domain.effBalance(genesis.recipient) > 0 shouldBe true
      domain.effBalance(masterToAlice.recipient) shouldBe 0
      domain.effBalance(aliceToBob.recipient) shouldBe 0
    }
  }
}
