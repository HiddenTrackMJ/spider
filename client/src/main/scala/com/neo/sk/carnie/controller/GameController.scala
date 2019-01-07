package com.neo.sk.carnie.controller

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.ActorRef
import com.neo.sk.carnie.Boot
import com.neo.sk.carnie.common.{Constant, Context}
import com.neo.sk.carnie.paperClient.Protocol._
import com.neo.sk.carnie.paperClient._
import com.neo.sk.carnie.scene.{GameScene, LayeredGameScene, PerformanceTool, SelectScene}
import javafx.animation.{Animation, AnimationTimer, KeyFrame, Timeline}
import javafx.scene.input.KeyCode
import javafx.util.Duration
import akka.actor.typed.scaladsl.adapter._
import org.slf4j.LoggerFactory
import com.neo.sk.carnie.actor.PlayGameWebSocket
import com.neo.sk.carnie.paperClient.ClientProtocol.PlayerInfoInClient
import javafx.scene.media.{AudioClip, Media, MediaPlayer}
import java.awt.event.KeyEvent
//import org.seekloud.esheepapi.pb.observations.{ImgData, LayeredObservation}

/**
  * Created by dry on 2018/10/29.
  **/

class GameController(player: PlayerInfoInClient,
                     stageCtx: Context,
                     gameScene: GameScene,
//                     layeredGameScene: LayeredGameScene,
                     mode: Int =0,
                     frameRate: Int
                     ) {

  private[this] val log = LoggerFactory.getLogger(this.getClass)

  private val playActor = Boot.system.spawn(PlayGameWebSocket.create(this), "playActor")

  var isGetKiller = false
  var killerInfo: scala.Option[String] = None
  var currentRank = List.empty[Score]
  val bounds = Point(Boundary.w, Boundary.h)
  var grid = new GridOnClient(bounds)
  var firstCome = true
  val idGenerator = new AtomicInteger(1)
  var playBgm = true
  var isSynced = false
  var isWin = false
  var exitFullScreen = false
  var winnerName = "unknown"
  var isContinues = true
  var btnFlag = true
  var winnerData : Option[Protocol.Data4TotalSync] = None
  val audioFinish = new AudioClip(getClass.getResource("/mp3/finish.mp3").toString)
  val audioKill = new AudioClip(getClass.getResource("/mp3/kill.mp3").toString)
  val audioWin = new AudioClip(getClass.getResource("/mp3/win.mp3").toString)
  val audioDie = new AudioClip(getClass.getResource("/mp3/killed.mp3").toString)
  val bgm4 = new AudioClip(getClass.getResource("/mp3/bgm4.mp3").toString)
  var pingMap = Map.empty[Short, Long] // id, 时间戳

  var pingId: Short = 0

  val bgmList = List(bgm4)
  var BGM = new AudioClip(getClass.getResource("/mp3/bgm4.mp3").toString)
  private val bgmAmount = bgmList.length
  var syncFrame: scala.Option[Protocol.SyncFrame] = None
  var syncGridData: scala.Option[Protocol.Data4TotalSync] = None
  var drawFunction: FrontProtocol.DrawFunction = FrontProtocol.DrawGameWait
  private var recallFrame: scala.Option[Int] = None
  private var stageWidth = stageCtx.getStage.getWidth.toInt
  private var stageHeight = stageCtx.getStage.getHeight.toInt
  private var isContinue = true
  private var myScore = BaseScore(0, 0, 0)
  private var maxArea: Int = 0
  private var winningData = WinData(0,Some(0))
  private val timeline = new Timeline()
  private var logicFrameTime = System.currentTimeMillis()
  private val animationTimer = new AnimationTimer() {
    override def handle(now: Long): Unit = {
      draw(System.currentTimeMillis() - logicFrameTime)
    }
  }

  def loseConnect(): Unit = {
    gameScene.drawGameOff(firstCome)
//    layeredGameScene.drawGameOff(firstCome)
  }

  def getRandom(s: Int):Int ={
    val rnd = new scala.util.Random
    rnd.nextInt(s)
  }

  def start(domain: String, mode: Int, img: Int): Unit = {
//    println(s"got accCode:${player.msg}")
    playActor ! PlayGameWebSocket.ConnectGame(player, domain, mode, img)
    addUserActionListen()
    startGameLoop()
  }

  def joinByRoomId(domain: String, roomId: Int, img: Int): Unit = {
    playActor ! PlayGameWebSocket.JoinByRoomId(player, domain, roomId, img)
    addUserActionListen()
    startGameLoop()
  }

  def createRoom(domain: String, mode: Int, img: Int, pwd: String): Unit = {
    playActor ! PlayGameWebSocket.CreateRoom(player, domain, mode, img, pwd)
    addUserActionListen()
    startGameLoop()
  }

  def startGameLoop(): Unit = { //渲染帧
    BGM = bgmList(getRandom(bgmAmount))
    logicFrameTime = System.currentTimeMillis()
    timeline.setCycleCount(Animation.INDEFINITE)
    val keyFrame = new KeyFrame(Duration.millis(frameRate), { _ =>
      logicLoop()
    })
    timeline.getKeyFrames.add(keyFrame)
    animationTimer.start()
    timeline.play()
  }

  private def logicLoop(): Unit = { //逻辑帧
    if(!stageCtx.getStage.isFullScreen && !exitFullScreen) {
      gameScene.resetScreen(Constant.CanvasWidth,Constant.CanvasHeight,Constant.CanvasWidth,Constant.CanvasHeight)
      stageCtx.getStage.setWidth(Constant.CanvasWidth)
      stageCtx.getStage.setHeight(Constant.CanvasHeight)
      exitFullScreen = true
    }
    if(stageWidth != stageCtx.getStage.getWidth.toInt || stageHeight != stageCtx.getStage.getHeight.toInt){
      stageWidth = stageCtx.getStage.getWidth.toInt
      stageHeight = stageCtx.getStage.getHeight.toInt
      gameScene.resetScreen(stageWidth,stageHeight,stageWidth,stageHeight)
      stageCtx.getStage.setWidth(stageWidth)
      stageCtx.getStage.setHeight(stageHeight)
    }
    logicFrameTime = System.currentTimeMillis()
    if (pingId > 10000) pingId = 0  else pingId = (pingId + 1).toShort
    val curTime = System.currentTimeMillis()
    pingMap += (pingId -> curTime)
    playActor ! PlayGameWebSocket.MsgToService(Protocol.SendPingPacket(pingId))

    recallFrame match {
      case Some(-1) =>
        println("!!!!!!!!:NeedToSync")
        playActor ! PlayGameWebSocket.MsgToService(NeedToSync.asInstanceOf[UserAction])
        recallFrame = None

      case Some(frame) =>
        val time1 = System.currentTimeMillis()
        println(s"before recall...frame:${grid.frameCount}")
        grid.historyDieSnake.filter{d => d._2.contains(player.id) && d._1 > frame}.keys.headOption match {
          case Some(dieFrame) =>
            if (dieFrame - 2 > frame) grid.recallGrid(frame, dieFrame - 2)
            else grid.setGridInGivenFrame(frame)

          case None =>
            grid.recallGrid(frame, grid.frameCount)
        }
        println(s"after recall time: ${System.currentTimeMillis() - time1}...after frame:${grid.frameCount}")
        recallFrame = None

      case None =>
    }

    if (syncGridData.nonEmpty) { //全量数据
      if(grid.snakes.nonEmpty){
        println("total syncGridData")
        grid.historyStateMap += grid.frameCount -> (grid.snakes, grid.grid, grid.snakeTurnPoints)
      }
      grid.initSyncGridData(syncGridData.get)
      addBackendInfo4Sync(grid.frameCount)
      syncGridData = None
    } else if (syncFrame.nonEmpty) { //局部数据仅同步帧号
      val frontend = grid.frameCount
      val backend = syncFrame.get.frameCount
      val advancedFrame = Math.abs(backend - frontend)
      if (backend > frontend && advancedFrame == 1) {
        println(s"backend advanced frontend,frontend$frontend,backend:$backend")
        addBackendInfo(grid.frameCount)
        grid.updateOnClient()
        addBackendInfo(grid.frameCount)
      } else if(frontend > backend && grid.historyStateMap.get(backend).nonEmpty){
        println(s"frontend advanced backend,frontend$frontend,backend:$backend")
        grid.setGridInGivenFrame(backend)
      } else if (backend >= frontend && advancedFrame < (grid.maxDelayed - 1)) {
        println(s"backend advanced frontend,frontend$frontend,backend:$backend")
        val endFrame = grid.historyDieSnake.filter{d => d._2.contains(player.id) && d._1 > frontend}.keys.headOption match {
          case Some(dieFrame) => Math.min(dieFrame - 1, backend)
          case None => backend
        }
        (frontend until endFrame).foreach { frame =>
          grid.frameCount = frame
          addBackendInfo(frame)
          grid.updateOnClient()
        }
        println(s"after speed,frame:${grid.frameCount}")
      } else {
        playActor ! PlayGameWebSocket.MsgToService(NeedToSync.asInstanceOf[UserAction])
      }
      syncFrame = None
    } else {
      addBackendInfo(grid.frameCount)
      grid.updateOnClient()
      addBackendInfo(grid.frameCount)
    }

//    if (newSnakeInfo.nonEmpty) {
//      //        println(s"newSnakeInfo: ${newSnakeInfo.get.snake.map(_.id)}")
//      if (newSnakeInfo.get.snake.map(_.id).contains(player.id) && !firstCome) spaceKey()
//      newSnakeInfo.get.snake.foreach { s =>
//        grid.cleanSnakeTurnPoint(s.id) //清理死前拐点
//      }
//      grid.snakes ++= newSnakeInfo.get.snake.map(s => s.id -> s).toMap
//      grid.addNewFieldInfo(NewFieldInfo(newSnakeInfo.get.frameCount, newSnakeInfo.get.filedDetails))
//
//      newSnakeInfo = None
//    }
//
//    if (syncGridData.nonEmpty) { //逻辑帧更新数据
//      grid.initSyncGridData(syncGridData.get)
//      syncGridData = None
//    } else {
//      grid.update("f")
////      println(s"update: ${grid.getGridData.snakes.map(_.id)}")
//    }
//
//    if (newFieldInfo.nonEmpty) {
//      val minFrame = newFieldInfo.keys.min
//      (minFrame to grid.frameCount).foreach { frame =>
//        if (newFieldInfo.get(frame).nonEmpty) {
//          val newFieldData = newFieldInfo(frame)
//          if (newFieldData.fieldDetails.map(_.uid).contains(player.id))
//            println("after newFieldInfo, my turnPoint:" + grid.snakeTurnPoints.get(player.id))
//          grid.addNewFieldInfo(newFieldData)
//          newFieldInfo -= frame
//        }
//      }
//    }


    val gridData = grid.getGridData

    gridData.snakes.find(_.id == player.id) match {
      case Some(_) =>
        if(firstCome)
          firstCome = false
//        if(playBgm) {
//          BGM.play(30)
//          playBgm = false
//        }
//        if(!BGM.isPlaying){
//          BGM = bgmList(getRandom(bgmAmount))
//          BGM.play(30)
//        }
//        val offsetTime = System.currentTimeMillis() - logicFrameTime
//        val a = System.currentTimeMillis()
//        layeredGameScene.draw(currentRank,player.id, gridData, offsetTime, grid, currentRank.headOption.map(_.id).getOrElse(player.id))
//        val x = getAllImage
//        val b = System.currentTimeMillis()
//        println("drawTime:" + (b-a) )
//        println(s"${grid.getGridData.snakes.map(_.id)}")
        drawFunction = FrontProtocol.DrawBaseGame(gridData)



      case None if isWin =>

      case None if isGetKiller && !firstCome =>
        if(btnFlag) {
          gameScene.group.getChildren.add(gameScene.backBtn)
          btnFlag = false
        }
        FrontProtocol.DrawGameDie(killerInfo)


      case _ =>
        drawFunction = FrontProtocol.DrawGameWait
    }
  }

  def draw(offsetTime: Long): Unit = {
    drawFunction match {
      case FrontProtocol.DrawGameWait =>
        if(BGM.isPlaying){
          BGM.stop()
        }
        gameScene.drawGameWait()
//        layeredGameScene.drawGameWait()

      case FrontProtocol.DrawGameOff =>
        if(BGM.isPlaying){
          BGM.stop()
        }
        gameScene.drawGameOff(firstCome)
//        layeredGameScene.drawGameOff(firstCome)

      case FrontProtocol.DrawGameWin(winner, winData) =>
        if(BGM.isPlaying){
          BGM.stop()
        }
        gameScene.drawGameWin(player.id, winner, winData,winningData)
//        layeredGameScene.drawGameWin(player.id, winner, winData,winningData)
        isContinue = false

      case FrontProtocol.DrawBaseGame(data) =>
        gameScene.draw(player.id, data, offsetTime, grid, currentRank.headOption.map(_.id).getOrElse(player.id))

        if (grid.killInfo.nonEmpty) {
          val killBaseInfo = grid.killInfo.get
          gameScene.drawBarrage(killBaseInfo._2, killBaseInfo._3)
          grid.barrageDuration -= 1
          if (grid.barrageDuration == 0) grid.killInfo = None
        }

      case FrontProtocol.DrawGameDie(killerName) =>
        if(BGM.isPlaying){
          BGM.stop()
        }
//        if (isContinue) audioDie.play()
        gameScene.drawGameDie(killerName, myScore, maxArea)
//        layeredGameScene.drawGameDie(killerName, myScore, maxArea)
        grid.killInfo = None
        isContinue = false
    }
  }

  def gameMessageReceiver(msg: WsSourceProtocol.WsMsgSource): Unit = {
    msg match {
      case Protocol.Id(id) =>
        log.debug(s"i receive my id:$id")

      case r@Protocol.SnakeAction(carnieId, keyCode, frame, actionId) =>
        if (grid.snakes.contains(grid.carnieMap.getOrElse(carnieId, ""))) {
          val id = grid.carnieMap(carnieId)
          if (id == player.id) { //收到自己的进行校验是否与预判一致，若不一致则回溯
            if (grid.myActionHistory.get(actionId).isEmpty) { //前端没有该项，则加入
              grid.addActionWithFrame(id, keyCode, frame)
              if (frame < grid.frameCount) {
                println(s"recall for my Action1,backend:$frame,frontend:${grid.frameCount}")
                recallFrame = grid.findRecallFrame(frame, recallFrame)
              }
            } else {
              if (grid.myActionHistory(actionId)._1 != keyCode || grid.myActionHistory(actionId)._2 != frame) { //若keyCode或则frame不一致则进行回溯
                grid.deleteActionWithFrame(id, grid.myActionHistory(actionId)._2)
                grid.addActionWithFrame(id, keyCode, frame)
                val miniFrame = Math.min(frame, grid.myActionHistory(actionId)._2)
                if (miniFrame < grid.frameCount) {
                  println(s"recall for my Action2,backend:$miniFrame,frontend:${grid.frameCount}")
                  recallFrame = grid.findRecallFrame(miniFrame, recallFrame)
                }
              }
              grid.myActionHistory -= actionId
            }
          }
        }

      case OtherAction(carnieId, keyCode, frame) =>
        if (grid.snakes.contains(grid.carnieMap.getOrElse(carnieId, ""))) {
          val id = grid.carnieMap(carnieId)
          grid.addActionWithFrame(id, keyCode, frame)
          if (frame < grid.frameCount) {
            println(s"recall for other Action,backend:$frame,frontend:${grid.frameCount}")
            recallFrame = grid.findRecallFrame(frame, recallFrame)
          }
        }

      case Protocol.SomeOneWin(winner) =>
        Boot.addToPlatform {
          val finalData = grid.getGridData
          drawFunction = FrontProtocol.DrawGameWin(winner, finalData)
//          winnerName = winner
//          winnerData = Some(finalData)
          isWin = true
//          audioWin.play()
          //        gameScene.drawGameWin(player.id, winner, finalData)
          grid.cleanData()
        }

      case x@Protocol.WinnerBestScore(score) =>
        log.debug(s"receive winnerBestScore msg:$x")
          maxArea = Math.max(maxArea,score)

      case UserLeft(id) =>
        Boot.addToPlatform {
          println(s"user $id left:::")
          grid.carnieMap = grid.carnieMap.filterNot(_._2 == id)
          grid.cleanDiedSnakeInfo(id)
        }

      case x@Protocol.DeadPage(kill, area, playTime) =>
        println(s"recv userDead $x")
        Boot.addToPlatform {
          myScore = BaseScore(kill, area, playTime)
        }


      case Protocol.Ranks(current, score, rank, currentNum) =>
        Boot.addToPlatform {
          currentRank = current
          maxArea = Math.max(maxArea, score.area)
          if (grid.getGridData.snakes.exists(_.id == player.id) && !isWin && isSynced){
            gameScene.drawRank(player.id, grid.getGridData.snakes, current, score, rank, currentNum)
//            layeredGameScene.drawRank(player.id, grid.getGridData.snakes, current)
//            layeredGameScene.drawHumanRank(player.id, grid.getGridData.snakes, currentRank)
          }
        }

      case data: Protocol.Data4TotalSync =>
        Boot.addToPlatform{
          syncGridData = Some(data)
          isSynced = true
        }

      case data: Protocol.SyncFrame =>
        syncFrame = Some(data)

      case data: Protocol.NewSnakeInfo =>
        Boot.addToPlatform{
          data.snake.foreach { s => grid.carnieMap += s.carnieId -> s.id }
          grid.historyNewSnake += data.frameCount -> (data.snake, data.filedDetails.map { f =>
            FieldByColumn(grid.carnieMap.getOrElse(f.uid, ""), f.scanField)
          })
          if (data.frameCount < grid.frameCount) {
            println(s"recall for NewSnakeInfo,backend:${data.frameCount},frontend:${grid.frameCount}")
            recallFrame = grid.findRecallFrame(data.frameCount - 1, recallFrame)
          }
        }

      case Protocol.UserDeadMsg(frame, deadInfo) =>
        Boot.addToPlatform{
          deadInfo.foreach{d =>
            if (grid.carnieMap.get(d.carnieId).isDefined){
              if (grid.carnieMap(d.carnieId) == player.id){
                if (d.killerId.isDefined){
                  val killerId = grid.carnieMap(d.killerId.get)
                  isGetKiller = true
                  if (grid.snakes.get(killerId).isDefined){
                    killerInfo = Some(grid.snakes(killerId).name)
                  }
                }
                else {
                  isGetKiller = true
                  killerInfo = None
                }
              }
            }
          }
          val deadList = deadInfo.map(baseInfo => grid.carnieMap.getOrElse(baseInfo.carnieId, ""))
          grid.historyDieSnake += frame -> deadList
          deadInfo.filter(_.killerId.nonEmpty).foreach { i =>
            val idOp = grid.carnieMap.get(i.carnieId)
            if (idOp.nonEmpty) {
              val id = idOp.get
              val name = grid.snakes.get(id).map(_.name).getOrElse("unknown")
              val killerName = grid.snakes.get(grid.carnieMap.getOrElse(i.killerId.get, "")).map(_.name).getOrElse("unknown")
              grid.killInfo = Some(id, name, killerName)
              grid.barrageDuration = 100
            }
          }
          if (frame < grid.frameCount) {
            println(s"recall for UserDeadMsg,backend:$frame,frontend:${grid.frameCount}")
            val deadRecallFrame = if (deadList.contains(player.id)) frame - 2 else frame - 1
            recallFrame = grid.findRecallFrame(deadRecallFrame, recallFrame)
          }
        }


      case data: Protocol.NewFieldInfo =>
        Boot.addToPlatform{
          val fields = data.fieldDetails.map{f =>FieldByColumn(grid.carnieMap.getOrElse(f.uid, ""), f.scanField)}
          if (fields.exists(_.uid == player.id)) audioFinish.play()
          grid.historyFieldInfo += data.frameCount -> fields
          if (data.frameCount < grid.frameCount) {
            println(s"recall for NewFieldInfo,backend:${data.frameCount},frontend:${grid.frameCount}")
            recallFrame = grid.findRecallFrame(data.frameCount - 1, recallFrame)
          }
        }

      case x@Protocol.ReceivePingPacket(actionId) =>
        Boot.addToPlatform{
          if (pingMap.get(actionId).nonEmpty) {
            PerformanceTool.receivePingPackage(pingMap(actionId))
            pingMap -= actionId
          }
        }

      case x@Protocol.WinData(_,_) =>
        log.debug(s"receive winningData msg:$x")
        winningData = x

      case unknown@_ =>
        log.debug(s"i receive an unknown msg:$unknown")
    }
  }

  def addUserActionListen():Unit = {
    gameScene.viewCanvas.requestFocus()

    gameScene.viewCanvas.setOnKeyPressed{ event =>
      val key = event.getCode
      if (Constant.watchKeys.contains(key)) {
        val delay = if(mode==2) 4 else 2
        val frame = grid.frameCount + delay
//        val actionId = idGenerator.getAndIncrement()
        val keyCode = Constant.keyCode2Byte(key)
        grid.addActionWithFrame(player.id, keyCode, frame)
        key match {
          case KeyCode.SPACE =>
            val msg: Protocol.UserAction = PressSpace
            playActor ! PlayGameWebSocket.MsgToService(msg)

          case _ =>
            val newKeyCode = Constant.keyCode2Byte(
              if (mode == 1)
                key match {
                  case KeyCode.LEFT => KeyCode.RIGHT
                  case KeyCode.RIGHT => KeyCode.LEFT
                  case KeyCode.DOWN => KeyCode.UP
                  case KeyCode.UP => KeyCode.DOWN
                  case _ => KeyCode.SPACE
                } else key)

            val actionInfo = grid.getUserMaxActionFrame(player.id, frame)
            if(actionInfo._1 < frame + maxContainableAction && actionInfo._2 != newKeyCode) {
              val actionId = idGenerator.getAndIncrement()
              grid.addActionWithFrame(player.id, newKeyCode, actionInfo._1)
              grid.myActionHistory += actionId -> (newKeyCode, actionInfo._1)
              val msg: Protocol.UserAction = Protocol.Key(newKeyCode, frame, actionId)
              playActor ! PlayGameWebSocket.MsgToService(msg)
            }
        }
      }
//        if (key != KeyCode.SPACE) {
//          grid.myActionHistory += actionId -> (keyCode, frame)
//        } else {
//          //数据重置
//          drawFunction match {
//            case FrontProtocol.DrawBaseGame(_) =>
//            case _ =>
//              val msg: Protocol.UserAction = PressSpace
//              playActor ! PlayGameWebSocket.MsgToService(msg)
//              grid.cleanData()
//              drawFunction = FrontProtocol.DrawGameWait
//              audioWin.stop()
//              audioDie.stop()
//              firstCome = true
//              if(isWin){
//                isWin = false
//                winnerName = "unknown"
//              }
//              animationTimer.start()
//              isContinue = true
//              gameScene.group.getChildren.remove(gameScene.backBtn)
//              btnFlag = true
//          }
//        }
//        val newKeyCode =
//          if(mode == 1)
//            key match {
//              case KeyCode.LEFT => KeyCode.RIGHT
//              case KeyCode.RIGHT => KeyCode.LEFT
//              case KeyCode.DOWN => KeyCode.UP
//              case KeyCode.UP => KeyCode.DOWN
//              case _ => KeyCode.SPACE
//            }
//          else key
//        println(s"onkeydown：${Key(player.id, Constant.keyCode2Int(newKeyCode), frame, actionId)}")
//        playActor ! PlayGameWebSocket.MsgToService(Protocol.Key(player.id, Constant.keyCode2Int(newKeyCode), frame, actionId))
//      }
    }

    gameScene.backBtn.setOnAction(_ => switchToSelecting())
  }

  def switchToSelecting():Unit = {
    //fixme 假设用户一次玩游戏时间不超过两小时，否则需要刷新token
    Boot.addToPlatform{
      println("come back to selectScene.")
      stageCtx.getStage.setFullScreen(false)
      Boot.system.stop(playActor.toUntyped)
      Boot.addToPlatform{
        val selectScene = new SelectScene()
        new SelectController(player, selectScene, stageCtx).showScene
      }
    }
  }

  def spaceKey(): Unit = {
    grid.cleanSnakeTurnPoint(player.id)
    grid.actionMap = grid.actionMap.filterNot(_._2.contains(player.id))
    drawFunction = FrontProtocol.DrawGameWait
    firstCome = true
    if (isWin) isWin = false
    myScore = BaseScore(0, 0, 0)
    isContinue = true
    isSynced = false
    Boot.addToPlatform{
      gameScene.group.getChildren.remove(gameScene.backBtn)
      btnFlag = true
    }
//    animationTimer.start()
    //                  backBtn.style.display="none"
    //                  rankCanvas.addEventListener("",null)
  }

  def addBackendInfo(frame: Int): Unit = {
    grid.historyFieldInfo.get(frame).foreach { data =>
      grid.addNewFieldInfo(data)
    }

    grid.historyDieSnake.get(frame).foreach { deadSnake =>
      deadSnake.foreach { sid =>
        grid.cleanDiedSnakeInfo(sid)
      }
    }

    grid.historyNewSnake.get(frame).foreach { newSnakes =>
      if (newSnakes._1.map(_.id).contains(player.id) && !firstCome && !isContinue) spaceKey()
      newSnakes._1.foreach { s => grid.cleanSnakeTurnPoint(s.id) } //清理死前拐点
      grid.snakes ++= newSnakes._1.map(s => s.id -> s).toMap
      grid.addNewFieldInfo(newSnakes._2)
    }
  }

  def addBackendInfo4Sync(frame: Int): Unit = {
    grid.historyNewSnake.get(frame).foreach { newSnakes =>
      if (newSnakes._1.map(_.id).contains(player.id) && !firstCome && !isContinue) spaceKey()
    }
  }

//  def getAllImage  = {
//    val imageList = layeredGameScene.layered.getAllImageData
//    val humanObservation : _root_.scala.Option[ImgData] = imageList.find(_._1 == "6").map(_._2)
//    val layeredObservation : LayeredObservation = LayeredObservation(
//      imageList.find(_._1 == "0").map(_._2),
//      imageList.find(_._1 == "1").map(_._2),
//      imageList.find(_._1 == "2").map(_._2),
//      imageList.find(_._1 == "3").map(_._2),
//      imageList.find(_._1 == "4").map(_._2),
//      imageList.find(_._1 == "5").map(_._2)
//    )
//    (humanObservation,layeredObservation, grid.frameCount.toInt)
//  }
}
