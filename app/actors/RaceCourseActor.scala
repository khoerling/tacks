package actors

import scala.concurrent.duration._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor._
import reactivemongo.bson.BSONObjectID
import org.joda.time.DateTime

import models._
import tools.Conf

case class RaceCourseState(
  raceCourse: RaceCourse,
  nextRun: Option[RaceCourseRun],
  liveRuns: Seq[RaceCourseRun]
) {
  def runLeaderboard(runId: BSONObjectID): Seq[PlayerTally] = {
    liveRuns.find(_.id == runId).map(_.leaderboard).getOrElse(Nil)
  }

  def playerRun(playerId: BSONObjectID): Option[RaceCourseRun] = {
    liveRuns.find(_.playerIds.contains(playerId)).orElse(nextRun)
  }

  def withUpdatedRun(run: RaceCourseRun): RaceCourseState = {
    if (nextRun.map(_.id).contains(run.id)) copy(nextRun = Some(run))
    else liveRuns.indexWhere(_.id == run.id) match {
      case -1 => this
      case i => copy(liveRuns = liveRuns.updated(i, run))
    }
  }

  def escapePlayer(playerId: BSONObjectID): RaceCourseState = {
    copy(
      nextRun = nextRun.map(_.removePlayerId(playerId)),
      liveRuns = liveRuns.map(_.removePlayerId(playerId))
    )
  }
}

case object RotateNextRun

class RaceCourseActor(raceCourse: RaceCourse) extends Actor with ManageWind {

  val id = BSONObjectID.generate
  val course = raceCourse.course

  var state = RaceCourseState(
    raceCourse = raceCourse,
    nextRun = None,
    liveRuns = Nil
  )

  val players = scala.collection.mutable.Map[String, PlayerContext]()

  def clock: Long = DateTime.now.getMillis

  val ticks = Seq(
    Akka.system.scheduler.schedule(1.second, 1.second, self, RotateNextRun),
    Akka.system.scheduler.schedule(0.seconds, course.gustGenerator.interval.seconds, self, SpawnGust),
    Akka.system.scheduler.schedule(0.seconds, Conf.frameMillis.milliseconds, self, FrameTick)
  )

  def receive = {

    /**
     * player join => added to context Map
     */
    case PlayerJoin(player) => {
      players += player.id.stringify -> PlayerContext(player, KeyboardInput.initial, OpponentState.initial, sender())
    }

    /**
     * player quit => removed from context Map
     */
    case PlayerQuit(player) => {
      players -= player.id.stringify
    }

    /**
     * game heartbeat:
     * update wind (origin, speed and gusts positions)
     */
    case FrameTick => {
      updateWind()
    }

    /**
     * player update coming from websocket through player actor
     * context is updated, race started if requested
     */
    case PlayerUpdate(player, PlayerInput(opState, input, clientTime)) => {
      val id = player.id.stringify

      players.get(id).foreach { context =>
        val newContext = context.copy(input = input, state = opState)
        players += (id -> newContext)

        if (input.startCountdown) {
          state = RaceCourseActor.startCountdown(state, byPlayerId = player.id)
        }

        if (input.escapeRun) {
          state = state.escapePlayer(player.id)
        }

        if (context.state.crossedGates != newContext.state.crossedGates) {
          state = RaceCourseActor.gateCrossedUpdate(state, newContext, players.toMap)
        }

        context.ref ! raceUpdateForPlayer(player, clientTime)
      }
    }

    /**
     * new gust
     */
    case SpawnGust => generateGust()

    case RotateNextRun => {
      state = RaceCourseActor.rotateNextRun(state)
    }
  }

  def playerOpponents(playerId: String): Seq[Opponent] = {
    players.toSeq.filterNot(_._1 == playerId).map(_._2.asOpponent)
  }

  def playerLeaderboard(playerId: String): Seq[PlayerTally] = {
    state.playerRun(BSONObjectID(playerId)).map(_.id).map(state.runLeaderboard).getOrElse(Nil)
  }

  def raceUpdateForPlayer(player: Player, clientTime: Long) = {
    RaceUpdate(
      serverNow = DateTime.now,
      startTime = RaceCourseActor.playerStartTime(state, player),
      wind = wind,
      opponents = playerOpponents(player.id.stringify),
      leaderboard = playerLeaderboard(player.id.stringify),
      clientTime = clientTime
    )
  }

  override def postStop() = {
    ticks.foreach(_.cancel())
  }
}

object RaceCourseActor {
  def props(raceCourse: RaceCourse) = Props(new RaceCourseActor(raceCourse))

  def rotateNextRun(state: RaceCourseState): RaceCourseState = {
    state.nextRun match {
      case Some(nextRun) if nextRun.startTime.plusSeconds(state.raceCourse.countdown).isBeforeNow => {
        state.copy(nextRun = None, liveRuns = state.liveRuns :+ nextRun)
      }
      case _ => state
    }
  }

  def playerStartTime(state: RaceCourseState, player: Player): Option[DateTime] = {
    state.playerRun(player.id).map(_.startTime)
  }

  def gateCrossedUpdate(state: RaceCourseState, context: PlayerContext, players: Map[String,PlayerContext]): RaceCourseState = {
    state.playerRun(context.player.id).map { run =>

      val playerIds =
        if (context.state.crossedGates.length == 1) run.playerIds :+ context.player.id
        else run.playerIds

      val leaderboard = playerIds.map(_.stringify).flatMap(players.get).map { context =>
        PlayerTally(context.player.id, context.player.handleOpt, context.state.crossedGates)
      }.sortBy { pt =>
        (-pt.gates.length, pt.gates.headOption)
      }

      val updatedRun = run.copy(playerIds = playerIds, leaderboard = leaderboard)

      RaceCourseRun.upsert(updatedRun)

      state.withUpdatedRun(updatedRun)

    }.getOrElse(state)
  }

  def startCountdown(state: RaceCourseState, byPlayerId: BSONObjectID): RaceCourseState = {
    state.nextRun match {
      case None => {
        val run = RaceCourseRun(
          _id = BSONObjectID.generate,
          raceCourseId = state.raceCourse.id,
          startTime = DateTime.now.plusSeconds(state.raceCourse.countdown),
          playerIds = Nil,
          leaderboard = Nil
        )
        state.copy(nextRun = Some(run))
      }
      case Some(_) => state
    }
  }
}