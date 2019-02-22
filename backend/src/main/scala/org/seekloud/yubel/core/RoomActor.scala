package org.seekloud.yubel.core

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.seekloud.yubel.paperClient.Protocol._
import org.slf4j.LoggerFactory
import org.seekloud.yubel.paperClient._
import org.seekloud.yubel.Boot.roomManager
import org.seekloud.yubel.core.GameRecorder.RecordData
import org.seekloud.yubel.common.AppSettings
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import concurrent.duration._
//import org.seekloud.carnie.Boot.{executor, scheduler, timeout, tokenActor}
import org.seekloud.yubel.core.TokenActor.AskForToken
import akka.actor.typed.scaladsl.AskPattern._
import org.seekloud.yubel.core.BotActor.{BackToGame, BotDead, KillBot}
import org.seekloud.yubel.models.dao.PlayerRecordDAO
import org.seekloud.utils.EsheepClient
import scala.concurrent.Future

/**
  * Created by dry on 2018/10/12.
  **/

object RoomActor {

  private val log = LoggerFactory.getLogger(this.getClass)
  val border = Point(BorderSize.w, BorderSize.h)

  private val upperLimit = AppSettings.upperLimit.toFloat

  private val lowerLimit = AppSettings.lowerLimit.toFloat

  private val decreaseRate = AppSettings.decreaseRate

  private val fullSize = (BorderSize.w - 1) * (BorderSize.h - 1)

//  private val maxWaitingTime4Restart = 3000

  private val waitingTime4CloseWs = 15.minutes
//  private val waitingTime4CloseWs = 5.seconds


  //  private val classify = 5

  private final case object SyncKey

  private case object UserDeadTimerKey extends Command

  sealed trait Command

  case class UserActionOnServer(id: String, action: Protocol.UserAction) extends Command

  case class JoinRoom(id: String, name: String, subscriber: ActorRef[WsSourceProtocol.WsMsgSource], img: Int) extends Command

  case class JoinRoom4Bot(id: String, name: String, botActor: ActorRef[BotActor.Command], img: Int) extends Command

  case class LeftRoom(id: String, name: String) extends Command

  case class SnakeDead(roomId: Int, mode: Int, users: List[(String, Short, Short)]) extends Command with RoomManager.Command // (id, kill, area)

  case class UserDead(roomId: Int, mode: Int, users: List[(String, Long)]) extends Command with RoomManager.Command

  private case class ChildDead[U](name: String, childRef: ActorRef[U]) extends Command

  case class WatchGame(playerId: String, userId: String, subscriber: ActorRef[WsSourceProtocol.WsMsgSource]) extends Command

  case class WatcherLeftRoom(userId: String) extends Command

  case class CloseWs(userId: String) extends Command

  private case object Sync extends Command

  case class UserInfo(name: String, startTime: Long, joinFrame: Long, img: Int)

  final case class SwitchBehavior(
                                   name: String,
                                   behavior: Behavior[Command],
                                   durationOpt: Option[FiniteDuration] = None,
                                   timeOut: TimeOut = TimeOut("busy time error")
                                 ) extends Command

  case class TimeOut(msg: String) extends Command

  def create(roomId: Int, mode: Int): Behavior[Command] = {
    log.debug(s"Room Actor-$roomId start...")
    Behaviors.setup[Command] { ctx =>
      implicit val carnieIdGenerator: AtomicInteger = new AtomicInteger(1)
      Behaviors.withTimers[Command] {
        implicit timer =>
          val grid = new GridOnServer(border)
          val winStandard = fullSize * upperLimit
          //            implicit val sendBuffer = new MiddleBufferInJvm(81920)
          val frameRate = mode match {
            case 2 => Protocol.frameRate2
            case _ => Protocol.frameRate1
          }
          log.info(s"frameRate: $frameRate")
//          val botsList = AppSettings.botMap.take(AppSettings.minPlayerNum - 1).map { b =>
//            val id = "bot_" + roomId + b._1
//            getBotActor(ctx, id) ! BotActor.InitInfo(b._2, mode, grid, ctx.self)
//            (id, b._2)
//          }.toList
//          roomManager ! RoomManager.BotsJoinRoom(roomId, botsList)
          timer.startPeriodicTimer(SyncKey, Sync, frameRate millis)
          idle(roomId, mode, grid, tickCount = 0l, winStandard = winStandard)
      }
    }
  }

  def idle(roomId: Int,
           mode: Int,
           grid: GridOnServer,
           userMap: mutable.HashMap[String, UserInfo] = mutable.HashMap[String, UserInfo](),
           userDeadList: mutable.HashMap[String, Long] = mutable.HashMap.empty[String, Long],
           watcherMap: mutable.HashMap[String, (String, Long)] = mutable.HashMap[String, (String, Long)](), //(watcherId, (playerId, GroupId))
           subscribersMap: mutable.HashMap[String, ActorRef[WsSourceProtocol.WsMsgSource]] = mutable.HashMap[String, ActorRef[WsSourceProtocol.WsMsgSource]](),
           tickCount: Long,
           gameEvent: mutable.ArrayBuffer[(Long, GameEvent)] = mutable.ArrayBuffer[(Long, GameEvent)](),
           winStandard: Double,
           firstComeList: List[String] = List.empty[String],
           botMap: mutable.HashMap[String, ActorRef[BotActor.Command]] = mutable.HashMap[String, ActorRef[BotActor.Command]](),
           yubelMap: mutable.HashMap[String, Byte] = mutable.HashMap[String, Byte]() //(id -> carnie)
          )(implicit carnieIdGenerator: AtomicInteger, timer: TimerScheduler[Command]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case m@JoinRoom(id, name, subscriber, img) =>
          log.info(s"got JoinRoom $m")
          yubelMap.put(id, grid.generateCarnieId(carnieIdGenerator, yubelMap.values))
          userMap.put(id, UserInfo(name, System.currentTimeMillis(), tickCount, img))
          subscribersMap.put(id, subscriber)
          log.debug(s"subscribersMap: $subscribersMap")
          //          ctx.watchWith(subscriber, UserLeft(subscriber))
          grid.addBoard(id, roomId, name, img, yubelMap(id))
          dispatchTo(subscribersMap, id, Protocol.Id(id))
          gameEvent += ((grid.frameCount, JoinEvent(id, name)))
          log.info(s"userMap.size:${userMap.size}, minplayerNum:${AppSettings.minPlayerNum}, botMap.size:${botMap.size}")
          while (userMap.size > AppSettings.minPlayerNum && botMap.nonEmpty) {
            val killBot = botMap.head
            roomManager ! RoomManager.Left(killBot._1, userMap.getOrElse(killBot._1, UserInfo("", -1L, -1L, 0)).name)
            botMap.-=(killBot._1)
            userMap.-=(killBot._1)
            yubelMap.-=(killBot._1)
            getBotActor(ctx, killBot._1) ! BotActor.KillBot
          }
          idle(roomId, mode, grid, userMap, userDeadList, watcherMap, subscribersMap, tickCount, gameEvent, winStandard, id :: firstComeList, botMap, yubelMap)

        case m@JoinRoom4Bot(id, name, botActor, img) =>
          log.info(s"got: $m")
          yubelMap.put(id, grid.generateCarnieId(carnieIdGenerator, yubelMap.values))
          userMap.put(id, UserInfo(name, System.currentTimeMillis(), -1L, img))
          botMap.put(id, botActor)
          grid.addBoard(id, roomId, name, img, yubelMap(id))
          dispatchTo(subscribersMap, id, Protocol.Id(id))
          gameEvent += ((grid.frameCount, JoinEvent(id, name)))
          idle(roomId, mode, grid, userMap, userDeadList, watcherMap, subscribersMap, tickCount, gameEvent, winStandard, firstComeList, botMap, yubelMap)

        case m@WatchGame(playerId, userId, subscriber) =>
          log.info(s"got: $m")
          yubelMap.put(userId, grid.generateCarnieId(carnieIdGenerator, yubelMap.values))
          val truePlayerId = if (playerId == "unknown") userMap.head._1 else playerId
          watcherMap.put(userId, (truePlayerId, tickCount))
          subscribersMap.put(userId, subscriber)
          ctx.watchWith(subscriber, WatcherLeftRoom(userId))
          dispatchTo(subscribersMap, userId, Protocol.Id4Watcher(truePlayerId, userId))
          val img = if (userMap.get(playerId).nonEmpty) userMap.get(playerId).head.img else 0
          dispatchTo(subscribersMap, userId, Protocol.StartWatching(mode, img))
          val gridData = grid.getGridData
          dispatchTo(subscribersMap, userId, gridData)
          idle(roomId, mode, grid, userMap, userDeadList, watcherMap, subscribersMap, tickCount, gameEvent, winStandard, firstComeList, botMap, yubelMap)

        case UserDead(_, _, users) =>
          println("sssss")
          val frame = grid.frameCount
          val endTime = System.currentTimeMillis()
          users.foreach { u =>
            val id = u._1
            timer.startSingleTimer(UserDeadTimerKey + id, CloseWs(id), waitingTime4CloseWs)
            if (userMap.get(id).nonEmpty) {
              val name = userMap(id).name
              val startTime = userMap(id).startTime
//              grid.cleanSnakeTurnPoint(id)

              //              log.debug(s"user $id dead:::::")
              dispatchTo(subscribersMap, id, Protocol.DeadBoard(frame,u._2, ((endTime - startTime) / 1000).toShort))
              //              dispatch(subscribersMap, Protocol.UserDead(frame, id, name, killerName))
//              watcherMap.filter(_._2._1 == id).foreach { user =>
//                dispatchTo(subscribersMap, user._1, Protocol.DeadBoard(u._2, u._3, ((endTime - startTime) / 1000).toShort))
//              }
              //              log.debug(s"watchMap: ${watcherMap.filter(_._2._1==id)}, watchedId: $id")
              //上传战绩
//              if (subscribersMap.get(id).nonEmpty) { //bot的战绩不上传
//                val msgFuture: Future[String] = tokenActor ? AskForToken
//                msgFuture.map { token =>
//                  EsheepClient.inputBatRecord(id, name, u._2, 1, u._3.toFloat * 100 / fullSize, "", startTime, endTime, token)
//                }
//                PlayerRecordDAO.addPlayerRecord(id, name, u._2, 1, u._3.toFloat * 100 / fullSize, startTime, endTime)
//              } else getBotActor(ctx, id) ! BotDead //bot死亡消息发送
            }
            userDeadList += id -> endTime
          }
          var finalDieInfo = List.empty[BaseDeadInfo]
          gameEvent.filter(_._1 == frame).foreach { d =>
            d._2 match {
              case Protocol.UserDead(_, id, _, killerId) =>
                (yubelMap.get(id), killerId) match {
                  case (Some(killedInfo), Some(killer)) =>
                    finalDieInfo = BaseDeadInfo(killedInfo, yubelMap.get(killer)) :: finalDieInfo
                  case (Some(killedInfo), None) =>
                    finalDieInfo = BaseDeadInfo(killedInfo, None) :: finalDieInfo

                  case _ => log.error(s"when handle UserDead msg...can not find carnieID of $id")
                }
              case _ =>
            }
          }
          if (finalDieInfo.nonEmpty) dispatch(subscribersMap, UserDeadMsg(frame, finalDieInfo))
          Behaviors.same

        case LeftRoom(id, name) =>
          log.debug(s"LeftRoom:::$id")
          dispatch(subscribersMap, Protocol.UserLeft(id))
          yubelMap.-=(id)
          if (userDeadList.contains(id))  {
            userDeadList -= id
            timer.cancel(UserDeadTimerKey + id)
          }

          grid.removeSnake(id)
          grid.cleanDiedSnakeInfo(List(id))
          subscribersMap.get(id).foreach(r => ctx.unwatch(r))
          userMap.remove(id)
          subscribersMap.remove(id)
          watcherMap.filter(_._2._1 == id).keySet.foreach { i =>
            subscribersMap.remove(i)
          }
          if (!userDeadList.contains(id)) gameEvent += ((grid.frameCount, LeftEvent(id, name))) else userDeadList -= id
          if (userMap.size < AppSettings.minPlayerNum) {
            if (botMap.size == userMap.size) {
              botMap.foreach(b => getBotActor(ctx, b._1) ! KillBot)
              Behaviors.stopped
            } else {
              if (!AppSettings.botMap.forall(b => botMap.keys.toList.contains("bot_" + roomId + b._1))) {
                val newBot = AppSettings.botMap.filterNot(b => botMap.keys.toList.contains("bot_" + roomId + b._1)).head
                getBotActor(ctx, "bot_" + roomId + newBot._1) ! BotActor.InitInfo(newBot._2, mode, grid, ctx.self)
                roomManager ! RoomManager.BotsJoinRoom(roomId, List(("bot_" + roomId + newBot._1, newBot._2)))
              }
              Behaviors.same
            }
          } else Behaviors.same


        case WatcherLeftRoom(uid) =>
          log.debug(s"WatcherLeftRoom:::$uid")
          yubelMap.-=(uid)
          subscribersMap.get(uid).foreach(r => ctx.unwatch(r)) //
          subscribersMap.remove(uid)
          if (watcherMap.contains(uid)) {
            watcherMap.remove(uid)
          }
          Behaviors.same

        case CloseWs(id) =>
          log.info(s"close ws")
          dispatchTo(subscribersMap, id, Protocol.CloseWs)
          Behaviors.same

        case UserActionOnServer(id, action) =>
          action match {
            case a@Keys(keyCode, frameCount, actionId,typ) =>
//              println("i got key :" + a)
              if (grid.boardMap.get(id).nonEmpty) {

                val realFrame = grid.checkActionFrame(id, frameCount)
                grid.addActionWithFrame(id, keyCode, realFrame, typ)
//                println("action map: " + grid.boardActionMap.size)
                dispatchTo(subscribersMap, id, Protocol.BoardAction(grid.boardMap(id).yubelId, keyCode, realFrame, actionId, typ)) //发送自己的action

                dispatch(subscribersMap.filterNot(_._1 == id),
                  Protocol.OtherAction(grid.boardMap(id).yubelId, keyCode, realFrame, typ)) //给其他人发送消息
              }


            case SendPingPacket(pingId) =>
              dispatchTo(subscribersMap, id, Protocol.ReceivePingPacket(pingId))

            case NeedToSync =>
//              val data = grid.getGridData
              val data = grid.getAllData
              dispatchTo(subscribersMap, id, data)


            case PressSpace =>
              if (userDeadList.contains(id)) {
                timer.cancel(UserDeadTimerKey + id)
                val info = userMap.getOrElse(id, UserInfo("", -1L, -1L, 0))
                grid.addBoard(id, roomId, info.name, info.img,
                  yubelMap.get(id) match {
                    case Some(carnieId) => carnieId
                    case None =>
                      val newCarnieId = grid.generateCarnieId(carnieIdGenerator, yubelMap.values)
                      yubelMap.put(id, newCarnieId)
                      newCarnieId
                  })
                gameEvent += ((grid.frameCount, JoinEvent(id, userMap(id).name)))
                gameEvent += ((grid.frameCount, SpaceEvent(id)))
              }
            case x@_ => println("unknown msg " + x)
          }
          Behaviors.same

        case Sync =>
          val curTime = System.currentTimeMillis()
          val frame = grid.frameCount //即将执行改帧的数据
//          dispatch(subscribersMap,Protocol.SyncFrame(frame))
//          val shouldNewSnake = if (grid.waitingListState) true else false
          val shouldNewSnake = if (grid.waitingBoardState) true else false
          val finishFields = grid.updateInService(shouldNewSnake, roomId, mode) //frame帧的数据执行完毕
          val newData = grid.getGridData
          val allData = grid.getAllData
          if (allData.bricks.isEmpty) {
            subscribersMap.foreach( s =>
              dispatchTo(subscribersMap,s._1,Protocol.GameWin(grid.scoreMap(s._1))))
            grid.cleanData()
          }
//          println("bricks: " + allData.bricks.size)
          dispatch(subscribersMap.filter(s => firstComeList.contains(s._1)), allData)
          val endTime = System.currentTimeMillis()
          val deadList = grid.historyDieBoard.get(frame)
          if (deadList.nonEmpty) {
            val deadSnakesInfo = deadList.get.map { id =>
              if (grid.scoreMap.exists(_._1 == id)) {
                val info = grid.scoreMap.filter(_._1 == id).head
                (id, info._2.score)
              } else (id, -1L)
            }
            deadSnakesInfo.map{ u =>
              val id = u._1
              grid.boardMap -= id
              grid.ballMap -= id
              grid.scoreMap -= id
              timer.startSingleTimer(UserDeadTimerKey + id, CloseWs(id), waitingTime4CloseWs)
              if (userMap.get(id).nonEmpty) {
                val startTime = userMap(id).startTime
                dispatchTo(subscribersMap, id, Protocol.DeadBoard(frame,u._2, ((endTime - startTime) / 1000).toShort))
              }
              userDeadList += id -> endTime
            }
            dispatch(subscribersMap.filterNot{ s =>
              deadList.get.contains(s._1)}, Protocol.OthersDead(frame,deadList.get))
          }

          val newBoards = grid.newBoardInfo
          if (newBoards.isDefined) {
            val newBoardList = newBoards.get._1.keys.toList
            val newAllData = newBoards.get
            newBoards.get._1.foreach{ nb =>
                dispatchTo(subscribersMap, nb._1, allData)
            }
            dispatch(subscribersMap.filterNot(s =>
              newBoardList.contains(s._1)), Protocol.NewBoard
            (DataExceptBrick(frame,newAllData._1,newAllData._2,newAllData._3)))
          }
          grid.newBoardInfo = None


          var newField: List[Protocol.FieldByColumn] = Nil

          val newSnakesInfo = if (grid.newInfo.nonEmpty) { //有新的蛇
            val newSnakeField = grid.newInfo.map(n => (n._1, n._2.yubelId, n._3)).map { f =>
              val info = userMap.getOrElse(f._1, UserInfo("", -1L, -1L, 0))
              userMap.put(f._1, UserInfo(info.name, System.currentTimeMillis(), tickCount, info.img))
              if (f._1.take(3) == "bot" && userDeadList.contains(f._1)) getBotActor(ctx, f._1) ! BackToGame
              userDeadList -= f._1
              grid.zipFieldWithCondensed((f._2, f._3))
            }
            gameEvent += ((grid.frameCount, Protocol.NewSnakeInfo(grid.newInfo.map(_._2), newSnakeField)))
            Some(NewSnakeInfo(grid.newInfo.map(_._2), newSnakeField))
          } else None
          grid.newInfo = Nil

          val newFieldsInfo = if (finishFields.nonEmpty) { //发送圈地数据
            val zipFields = finishFields.filter(s => grid.snakes.get(s._1).nonEmpty).map(f => grid.zipField((f._1, grid.snakes(f._1).yubelId, f._2)))
            newField = zipFields.map(_._1)
            Some(zipFields.map(_._2))
          } else None

          if (newSnakesInfo.isDefined || newFieldsInfo.isDefined) {
            val message = Protocol.NewData(grid.frameCount, newSnakesInfo, newFieldsInfo)
            dispatch(subscribersMap.filterNot(s => firstComeList.contains(s._1)), message)
          }

          dispatch(subscribersMap.filter(s => firstComeList.contains(s._1)), newData)
          val init = grid.boardActionMap.map{ m =>
            m._2.toList.map{ a =>
              Protocol.OtherAction(yubelMap.getOrElse(a._1,0), a._2._1.toByte, m._1, a._2._2)
            }
          }.toList.flatten
          dispatch(subscribersMap.filter(s => firstComeList.contains(s._1)), InitActions(init))

          //错峰发送
          for ((u, i) <- userMap) {
            if ((tickCount - i.joinFrame) % 20 == 5 && grid.scoreMap.exists(_._1 == u)) {
              dispatchTo(subscribersMap, u, Protocol.ScoreData(grid.scoreMap))
            }
          }

          for ((u, i) <- userMap) {
            if ((tickCount - i.joinFrame) % 10 == 1 && grid.boardMap.exists(_._1 == u)) {
              dispatchTo(subscribersMap, u, Protocol.SyncFrame(grid.frameCount))
            }
          }


          if (grid.currentRank.nonEmpty && grid.currentRank.head.area >= winStandard) { //判断是否胜利
            log.info(s"win!! currentRank: ${grid.currentRank}")
            grid.cleanData()
            userMap.foreach { u =>
              if (u._1 == grid.currentRank.head.id) {
                dispatchToPlayerAndWatcher(
                  subscribersMap, watcherMap, u._1,
                  Protocol.WinData(grid.currentRank.head.area, None, userMap(grid.currentRank.head.id).name))
              }
              else {
                dispatchToPlayerAndWatcher(
                  subscribersMap, watcherMap, u._1,
                  Protocol.WinData(grid.currentRank.head.area,
                    grid.currentRank.filter(_.id == u._1).map(_.area).headOption, userMap(grid.currentRank.head.id).name)
                )
              }

            }

            gameEvent += ((grid.frameCount, Protocol.SomeOneWin(userMap(grid.currentRank.head.id).name)))
            userMap.foreach { u =>
              if (!userDeadList.contains(u._1)) {
                gameEvent += ((grid.frameCount, LeftEvent(u._1, u._2.name)))
                timer.startSingleTimer(UserDeadTimerKey + u._1, CloseWs(u._1), waitingTime4CloseWs)
                userDeadList += u._1 -> curTime
                if (u._1.take(3) == "bot") getBotActor(ctx, u._1) ! BotDead
              }
            }
          }

          val newWinStandard = if (grid.currentRank.nonEmpty) { //胜利条件的调整
            val maxSize = grid.currentRank.head.area
            if ((maxSize + fullSize * 0.1) < winStandard) fullSize * Math.max(upperLimit - userMap.size * decreaseRate, lowerLimit) else winStandard
          } else winStandard

          //for gameRecorder...
          val actionEvent = grid.getDirectionEvent(frame)
          val joinOrLeftEvent = gameEvent.filter(_._1 == frame)

          val baseEvent = if (tickCount % 10 == 3) RankEvent(grid.currentRank) :: (actionEvent ::: joinOrLeftEvent.map(_._2).toList) else actionEvent ::: joinOrLeftEvent.map(_._2).toList
          gameEvent --= joinOrLeftEvent
          val snapshot = Snapshot(newData.snakes, newData.bodyDetails, newData.fieldDetails)
          //          val snapshot = Snapshot(newData.snakes, newData.bodyDetails, newData.fieldDetails, newData.killHistory)
          val recordData = if (finishFields.nonEmpty) RecordData(frame, (EncloseEvent(newField) :: baseEvent, snapshot)) else RecordData(frame, (baseEvent, snapshot))
//          if (grid.snakes.nonEmpty || ctx.child("gameRecorder").nonEmpty) getGameRecorder(ctx, roomId, grid, mode) ! recordData
          idle(roomId, mode, grid, userMap, userDeadList, watcherMap, subscribersMap, tickCount + 1, gameEvent, newWinStandard, botMap = botMap, yubelMap = yubelMap)

        case ChildDead(child, childRef) =>
          log.debug(s"roomActor 不再监管 gameRecorder:$child,$childRef")
          ctx.unwatch(childRef)
          Behaviors.same

        case _ =>
          log.warn(s"${ctx.self.path} recv a unknow msg=$msg")
          Behaviors.unhandled
      }
    }

  }

  def dispatchTo(subscribers: mutable.HashMap[String, ActorRef[WsSourceProtocol.WsMsgSource]], id: String, gameOutPut: Protocol.GameMessage): Unit = {
    subscribers.get(id).foreach {
      _ ! gameOutPut
    }
  }

  def dispatch(subscribers: mutable.HashMap[String, ActorRef[WsSourceProtocol.WsMsgSource]], gameOutPut: Protocol.GameMessage): Unit = {
    subscribers.values.foreach {
      _ ! gameOutPut
    }
  }

  def dispatchToPlayerAndWatcher(subscribers: mutable.HashMap[String, ActorRef[WsSourceProtocol.WsMsgSource]],
                                 watcherMap: mutable.HashMap[String, (String, Long)],
                                 id: String, gameOutPut: Protocol.GameMessage): Unit = {
    (id :: watcherMap.filter(_._2._1 == id).keys.toList).foreach (u => dispatchTo(subscribers, u, gameOutPut))

  }

  private def getGameRecorder(ctx: ActorContext[Command], roomId: Int, grid: GridOnServer, mode: Int): ActorRef[GameRecorder.Command] = {
    val childName = "gameRecorder"
    ctx.child(childName).getOrElse {
      val newData = grid.getGridData
      val actor = ctx.spawn(GameRecorder.create(roomId, Snapshot(newData.snakes, newData.bodyDetails, newData.fieldDetails),
        GameInformation(roomId, System.currentTimeMillis(), 0, grid.frameCount, mode)), childName)
      ctx.watchWith(actor, ChildDead(childName, actor))
      actor
    }.upcast[GameRecorder.Command]
  }

  private def getBotActor(ctx: ActorContext[Command], botId: String) = {
    val childName = botId
    ctx.child(childName).getOrElse {
      val actor = ctx.spawn(BotActor.create(botId), childName)
      actor
    }.upcast[BotActor.Command]
  }


}
