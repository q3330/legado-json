package io.legado.app.ui.book.explore

internal enum class ExploreShowLayoutMode(val value: Int, val columns: Int) {
    LIST(0, 1),
    GRID_1(3, 1),
    GRID_2(2, 2),
    GRID(1, 3),
    GRID_4(4, 4);

    val isGrid: Boolean
        get() = this != LIST

    companion object {
        fun from(value: Int): ExploreShowLayoutMode {
            return entries.firstOrNull { it.value == value } ?: LIST
        }

        fun gridForColumns(columns: Int): ExploreShowLayoutMode = when (columns.coerceIn(1, 4)) {
            1 -> GRID_1
            2 -> GRID_2
            3 -> GRID
            else -> GRID_4
        }
    }
}
