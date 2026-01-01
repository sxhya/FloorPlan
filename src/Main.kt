//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import ui.FloorPlanApp
import javax.swing.SwingUtilities

fun main() {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    SwingUtilities.invokeLater {
        val app = FloorPlanApp()
        // No need to create a window here, FloorPlanApp's init creates the first document window
    }
}