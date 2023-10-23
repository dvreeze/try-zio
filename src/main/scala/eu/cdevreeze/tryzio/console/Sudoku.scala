/*
 * Copyright 2022-2022 Chris de Vreeze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cdevreeze.tryzio.console

import scala.util.chaining.*

import zio.*

/**
 * Sudoku solver. Pass the input as 9 times 9 numbers between 0 and 9, with 0 meaning empty.
 *
 * @author
 *   Chris de Vreeze
 */
object Sudoku extends ZIOAppDefault:

  final case class GridCellLocation(rowIdx: Int, colIdx: Int):
    require(rowIdx >= 0 && rowIdx < 9)
    require(colIdx >= 0 && colIdx < 9)

  final case class Cell(valueOption: Option[Int]):
    require(valueOption.forall(d => d >= 1 && d <= 9))

    def setValue(value: Int): Cell = Cell(Some(value))

  final case class SubgridRow(cells: Seq[Cell]):
    require(cells.sizeIs == 3)

  final case class Subgrid(rows: Seq[SubgridRow]):
    require(rows.sizeIs == 3)

    def isValid: Boolean =
      rows.flatMap(_.cells).flatMap(_.valueOption).pipe(values => values.size == values.distinct.size)

    def isFilled: Boolean = rows.forall(_.cells.forall(_.valueOption.nonEmpty))

    def remainingNumbers: Seq[Int] =
      // Note that the subgrid can be invalid
      (1 to 9).diff(rows.flatMap(_.cells).flatMap(_.valueOption))

    def show: String = rows.flatMap(_.cells).map(_.valueOption.getOrElse(0)).mkString(" ")

  final case class Row(cells: Seq[Cell]):
    require(cells.sizeIs == 9)

    def isValid: Boolean = cells.flatMap(_.valueOption).pipe(values => values.size == values.distinct.size)

    def isFilled: Boolean = cells.forall(_.valueOption.nonEmpty)

    def remainingNumbers: Seq[Int] =
      // Note that the row can be invalid
      (1 to 9).diff(cells.flatMap(_.valueOption))

    def setCellValue(colIdx: Int, value: Int): Row =
      cells.updated(colIdx, cells(colIdx).setValue(value)).pipe(Row.apply)

    def show: String = cells.map(_.valueOption.getOrElse(0)).mkString(" ")

  object Row:

    def fromNumbers(numbers: Seq[Int]): Row =
      require(numbers.sizeIs == 9)
      require(numbers.forall(n => n >= 0 && n <= 9))
      Row(numbers.map(n => Option(n).filter(_ >= 1)).map(Cell.apply))

  final case class Column(cells: Seq[Cell]):
    require(cells.sizeIs == 9)

    def isValid: Boolean = cells.flatMap(_.valueOption).pipe(values => values.size == values.distinct.size)

    def isFilled: Boolean = cells.forall(_.valueOption.nonEmpty)

    def remainingNumbers: Seq[Int] =
      // Note that the column can be invalid
      (1 to 9).diff(cells.flatMap(_.valueOption))

    def show: String = cells.map(_.valueOption.getOrElse(0)).mkString(" ")

  final case class Grid(rows: Seq[Row]):
    require(rows.sizeIs == 9)

    def columns: Seq[Column] = for { i <- 0 until 9 } yield Column(rows.map(r => r.cells(i)))

    def cell(rowIdx: Int, colIdx: Int): Cell = rows(rowIdx).cells(colIdx)

    def subgrids: Seq[Subgrid] =
      for {
        r <- Seq(0, 3, 6)
        c <- Seq(0, 3, 6)
      } yield subgridAt(r, c)

    def subgridAt(rowIdx: Int, colIdx: Int): Subgrid =
      val startRow: Int = 3 * (rowIdx / 3)
      val startCol: Int = 3 * (colIdx / 3)
      Subgrid(
        Seq(
          SubgridRow(Seq(cell(startRow, startCol), cell(startRow, startCol + 1), cell(startRow, startCol + 2))),
          SubgridRow(Seq(cell(startRow + 1, startCol), cell(startRow + 1, startCol + 1), cell(startRow + 1, startCol + 2))),
          SubgridRow(Seq(cell(startRow + 2, startCol), cell(startRow + 2, startCol + 1), cell(startRow + 2, startCol + 2)))
        )
      )

    def isValid: Boolean =
      rows.forall(_.isValid) && columns.forall(_.isValid) && subgrids.forall(_.isValid)

    def isFilled: Boolean = rows.forall(_.isFilled)

    def isSolved: Boolean = isValid && isFilled

    def setCellValue(rowIdx: Int, colIdx: Int, value: Int): Grid =
      val row: Row = rows(rowIdx).setCellValue(colIdx, value)
      rows.updated(rowIdx, row).pipe(Grid.apply)

    def unfilledGridCellLocations: Seq[GridCellLocation] =
      for {
        r <- 0 until 9
        c <- 0 until 9
        if cell(r, c).valueOption.isEmpty
      } yield GridCellLocation(r, c)

    def canSetCellValue(rowIdx: Int, colIdx: Int, value: Int): Boolean =
      val gridToValidate: Grid = setCellValue(rowIdx, colIdx, value)
      gridToValidate.rows(rowIdx).isValid &&
      gridToValidate.columns(colIdx).isValid &&
      gridToValidate.subgridAt(rowIdx, colIdx).isValid

    def remainingNumbersAt(rowIdx: Int, colIdx: Int): Seq[Int] =
      rows(rowIdx).remainingNumbers
        .filter(columns(colIdx).remainingNumbers.toSet)
        .filter(subgridAt(rowIdx, colIdx).remainingNumbers.toSet)

    def findNextUnfilledCellLocation: Option[GridCellLocation] =
      unfilledGridCellLocations.sortBy { loc =>
        remainingNumbersAt(loc.rowIdx, loc.colIdx).size
      }.headOption

    def show: String =
      rows.map(_.show).mkString("\n")

  object Grid:

    def fromNumbers(numbers: Seq[Int]): Grid =
      require(numbers.sizeIs == 9 * 9, s"Expected 9 * 9 numbers (0 for unfilled cells)")
      require(numbers.forall(n => n >= 0 && n <= 9), s"Expected only integers >= 0 and <= 9")

      numbers.grouped(9).toSeq.map(row => Row.fromNumbers(row)).pipe(rows => Grid(rows))

  end Grid

  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    val argsGetter: ZIO[ZIOAppArgs, Throwable, Chunk[String]] = getArgs
    for {
      args <- argsGetter
      grid <- ZIO.attempt { Grid.fromNumbers(args.map(_.toInt)).ensuring(_.isValid, "The start grid is not valid") }
      solutions <- findAllSolutions(grid).mapAttempt(_.distinct)
      _ <- ZIO.collectAllDiscard(solutions.map(grid => ZIO.logInfo(s"A found solution:\n${grid.show}")))
      _ <- ZIO.logInfo(s"Found ${solutions.size} solutions")
    } yield ()

  def findAllSolutions(startGrid: Grid): Task[Seq[Grid]] =
    runAllSteps(Seq(startGrid)).mapAttempt(_.filter(_.isSolved))

  private def runAllSteps(currentGrids: Seq[Grid]): Task[Seq[Grid]] =
    // Recursive
    for {
      stepResult <- runNextStep(currentGrids)
      result <- if stepResult == currentGrids then ZIO.succeed(currentGrids) else runAllSteps(stepResult)
    } yield result

  private def runNextStep(currentGrids: Seq[Grid]): Task[Seq[Grid]] =
    ZIO.logInfo("Running next step") *> ZIO.collectAll(currentGrids.map(runNextStep(_))).mapAttempt(_.flatten)

  private def runNextStep(currentGrid: Grid): Task[Seq[Grid]] =
    // No resulting grids means there are no solutions. If the currentGrid is a full solution, it is returned.
    for {
      _ <- ZIO.logInfo(s"Running step for grid:\n${currentGrid.show}")
      _ <- ZIO.fromTry(scala.util.Try(require(currentGrid.isValid, s"Not a valid grid:\n${currentGrid.show}")))
      nextGrids <-
        if currentGrid.isFilled then ZIO.succeed(Seq(currentGrid))
        else
          for {
            nextLoc <- ZIO.attempt { currentGrid.findNextUnfilledCellLocation.get }
            _ <- ZIO.logInfo(s"Found next location to fill: $nextLoc")
            remainingNumbers <- ZIO.attempt {
              currentGrid
                .remainingNumbersAt(nextLoc.rowIdx, nextLoc.colIdx)
                .filter(n => currentGrid.canSetCellValue(nextLoc.rowIdx, nextLoc.colIdx, n))
            }
            _ <- ZIO.logInfo(s"Remaining numbers that can be filled in at that location: ${remainingNumbers.mkString(", ")}")
            grids <- ZIO
              .attempt { remainingNumbers.map(n => currentGrid.setCellValue(nextLoc.rowIdx, nextLoc.colIdx, n)) }
              .mapAttempt(_.distinct)
          } yield grids
    } yield nextGrids

end Sudoku
