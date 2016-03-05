module Model.Shared (..) where

import Time exposing (Time)
import Dict exposing (Dict)
import Transit
import Hexagons
import Hexagons.Grid as HexGrid
import Constants
import Route


type alias Context =
  { player : Player
  , dims : ( Int, Int )
  , transition : Transit.Transition
  , routeTransition : Route.RouteTransition
  }


type alias FormResult a =
  Result FormErrors a


type alias FormErrors =
  Dict String (List String)


type alias Id =
  String


type alias Player =
  { id : Id
  , handle : Maybe String
  , status : Maybe String
  , avatarId : Maybe String
  , vmgMagnet : Int
  , guest : Bool
  , user : Bool
  }


type alias User =
  { id : Id
  , handle : String
  , status : Maybe String
  , avatarId : Maybe String
  , vmgMagnet : Int
  }


isAdmin : Player -> Bool
isAdmin player =
  case player.handle of
    Just h ->
      List.member h Constants.admins

    Nothing ->
      False


canUpdateDraft : Player -> Track -> Bool
canUpdateDraft player track =
  (track.status == Draft && player.id == track.creatorId) || isAdmin player


type alias LiveStatus =
  { liveTracks : List LiveTrack
  , onlinePlayers : List Player
  }


type alias LiveTrack =
  { track : Track
  , meta : TrackMeta
  , players : List Player
  , races : List Race
  }


type alias Track =
  { id : TrackId
  , name : String
  , creatorId : String
  , course : Course
  , status : TrackStatus
  }


type alias TrackId =
  Id


type alias TrackMeta =
  { creator : Player
  , rankings : List Ranking
  , runsCount : Int
  }


type TrackStatus
  = Draft
  | Open
  | Archived
  | Deleted


type alias Race =
  { id : String
  , trackId : String
  , startTime : Time
  , players : List Player
  , tallies : List PlayerTally
  }


type alias PlayerTally =
  { player : Player
  , gates : List Time
  , finished : Bool
  }


type alias Ranking =
  { rank : Int
  , player : Player
  , finishTime : Time
  }


type alias Message =
  { content : String
  , player : Player
  , time : Float
  }



-- Course


type alias Course =
  { upwind : Gate
  , downwind : Gate
  , grid : Grid
  , laps : Int
  , area : RaceArea
  , windSpeed : Int
  , windGenerator : WindGenerator
  , gustGenerator : GustGenerator
  }


type alias Gate =
  { y : Float
  , width : Float
  }


type alias RaceArea =
  { rightTop : Point
  , leftBottom : Point
  }


type GateLocation
  = DownwindGate
  | UpwindGate
  | StartLine


type alias WindGenerator =
  { wavelength1 : Int
  , amplitude1 : Int
  , wavelength2 : Int
  , amplitude2 : Int
  }


type alias GustGenerator =
  { interval : Int
  , radiusBase : Int
  , radiusVariation : Int
  , speedVariation : Range
  , originVariation : Range
  }


type alias Range =
  { start : Int
  , end : Int
  }


type alias Point =
  ( Float, Float )


type alias Dims =
  ( Int, Int )


type alias Segment =
  ( Point, Point )


type alias Coords =
  Hexagons.Axial



-- Grid


type alias Grid =
  Dict Coords TileKind


type alias Tile =
  { coords : Coords, kind : TileKind }


listGridTiles : Grid -> List Tile
listGridTiles grid =
  Dict.toList grid
    |> List.map (\( c, k ) -> Tile c k)


type TileKind
  = Water
  | Grass
  | Rock
