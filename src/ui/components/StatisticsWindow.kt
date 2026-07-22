package ui.components

import model.*
import model.Window as PlanWindow
import ui.FloorPlanDocument
import ui.ThreeDDocument
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class StatisticsWindow(title: String, tableModel: DefaultTableModel) : JFrame(title) {

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout()

        val table = JTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            rowHeight = 22
            tableHeader.reorderingAllowed = false
        }
        // Right-align all value columns (everything after the first "Metric" column)
        val rightRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
        for (c in 1 until table.columnCount) {
            table.columnModel.getColumn(c).cellRenderer = rightRenderer
        }
        // Give the Metric column a fixed preferred width so value columns share the rest
        table.columnModel.getColumn(0).preferredWidth = 160

        add(JScrollPane(table), BorderLayout.CENTER)
        pack()
        minimumSize = Dimension(420, 300)
        setLocationRelativeTo(null)
    }

    companion object {
        private val METRICS = listOf(
            "Wall Area (m²)",
            "Room Area (m²)",
            "Unusable Area (m²)",
            "Door Area (m²)",
            "Door Volume (m³)",
            "Window Area (m²)",
            "Window Volume (m³)",
            "Opening Volume (m³)",
            "Warnings"
        )
        // Printf format for each numeric metric row (index matches METRICS 0..7)
        private val FORMATS = listOf("%.2f", "%.2f", "%.2f", "%.2f", "%.3f", "%.2f", "%.3f", "%.3f")

        private data class FloorStats(
            val wallArea: Double,
            val roomArea: Double,
            val unusableArea: Double,
            val doorArea: Double,
            val doorVol: Double,
            val winArea: Double,
            val winVol: Double,
            val openingVol: Double,
            val warnings: List<String>
        )

        private fun compute(doc: FloorPlanDocument): FloorStats {
            val walls  = doc.elements.filterIsInstance<Wall>()
            val rooms  = doc.elements.filterIsInstance<Room>()
            val windows = doc.elements.filterIsInstance<PlanWindow>()
            val doors  = doc.elements.filterIsInstance<Door>()
            val stairs = doc.elements.filterIsInstance<Stairs>()
            val polys  = doc.elements.filterIsInstance<PolygonRoom>()

            val wallArea = walls.sumOf { it.width.toDouble() * it.height } / 10000.0

            val roomArea = (rooms.sumOf { it.width.toDouble() * it.height }
                    + polys.sumOf { it.getArea() }) / 10000.0

            // Unusable: stairs + all elevated floors (zOffset > 0)
            val unusableArea = (stairs.sumOf { it.getArea() }
                    + rooms.filter { it.zOffset > 0 }.sumOf { it.width.toDouble() * it.height }
                    + polys.filter { it.zOffset > 0 }.sumOf { it.getArea() }) / 10000.0

            var doorAreaCm2 = 0.0; var doorVolCm3 = 0.0
            for (d in doors) {
                val wall = doc.findContainingWall(d.x, d.y, d.width, d.height)
                val ew = if (wall != null)
                    (if (wall.width < wall.height) d.height else d.width).toDouble()
                else maxOf(d.width, d.height).toDouble()
                doorAreaCm2 += ew * d.verticalHeight
                doorVolCm3  += d.getArea() * d.verticalHeight
            }

            var winAreaCm2 = 0.0; var winVolCm3 = 0.0
            for (w in windows) {
                val wall = doc.findContainingWall(w.x, w.y, w.width, w.height)
                val ew = if (wall != null)
                    (if (wall.width < wall.height) w.height else w.width).toDouble()
                else maxOf(w.width, w.height).toDouble()
                winAreaCm2 += ew * w.height3D
                winVolCm3  += w.getArea() * w.height3D
            }

            val warns = mutableListOf<String>()
            for (r in rooms) {
                val wallFill = walls
                    .filter { r.getBounds().contains(it.getBounds()) }
                    .sumOf { it.width.toDouble() * it.height }
                if (wallFill > r.getArea() * 0.5)
                    warns += "Room@(${r.x},${r.y}) >50% walls"
            }
            val ix = doc.calculateIntersections()
            if (ix.isNotEmpty()) warns += "${ix.size} intersection(s)"

            return FloorStats(
                wallArea, roomArea, unusableArea,
                doorAreaCm2 / 10000.0, doorVolCm3 / 1000000.0,
                winAreaCm2  / 10000.0, winVolCm3  / 1000000.0,
                (doorVolCm3 + winVolCm3) / 1000000.0,
                warns
            )
        }

        /** Ordered list of numeric field values matching METRICS indices 0..7. */
        private fun numericValues(s: FloorStats) = listOf(
            s.wallArea, s.roomArea, s.unusableArea, s.doorArea,
            s.doorVol,  s.winArea,  s.winVol,       s.openingVol
        )

        private fun newReadOnlyModel(vararg cols: String) =
            object : DefaultTableModel(cols, 0) {
                override fun isCellEditable(r: Int, c: Int) = false
            }

        /** Single-floor table: two columns — Metric | Value */
        private fun buildSingleFloorModel(doc: FloorPlanDocument): DefaultTableModel {
            val s = compute(doc)
            val model = newReadOnlyModel("Metric", "Value")
            numericValues(s).forEachIndexed { i, v ->
                model.addRow(arrayOf(METRICS[i], FORMATS[i].format(v)))
            }
            model.addRow(arrayOf(
                METRICS[8],
                if (s.warnings.isEmpty()) "—" else s.warnings.joinToString("; ")
            ))
            return model
        }

        /** Multi-floor table: Metric | Floor1 | Floor2 | … | Total */
        private fun buildMultiFloorModel(doc3d: ThreeDDocument): DefaultTableModel {
            val allStats   = doc3d.floors.map { compute(it.floorDoc) }
            val floorNames = doc3d.floors.map { it.name }
            val cols = (listOf("Metric") + floorNames + listOf("Total")).toTypedArray()
            val model = newReadOnlyModel(*cols)

            for (i in 0..7) {
                val vals = allStats.map { numericValues(it)[i] }
                val row: Array<Any> = (
                    listOf(METRICS[i])
                    + vals.map { FORMATS[i].format(it) }
                    + listOf(FORMATS[i].format(vals.sum()))
                ).toTypedArray()
                model.addRow(row)
            }

            // Warnings row: per-floor text, no meaningful total
            val warnCells: Array<Any> = (
                listOf(METRICS[8])
                + allStats.map { s ->
                    if (s.warnings.isEmpty()) "—" else s.warnings.joinToString("; ")
                }
                + listOf("—")
            ).toTypedArray()
            model.addRow(warnCells)

            return model
        }

        /** Open a statistics window scoped to a single floor-plan document. */
        fun forFloor(doc: FloorPlanDocument, floorTitle: String = "Floor Plan"): StatisticsWindow =
            StatisticsWindow("Statistics — $floorTitle", buildSingleFloorModel(doc))

        /** Open a statistics window covering all floors in a 3-D model. */
        fun for3D(doc3d: ThreeDDocument): StatisticsWindow =
            StatisticsWindow("Statistics — 3D Model", buildMultiFloorModel(doc3d))
    }
}
