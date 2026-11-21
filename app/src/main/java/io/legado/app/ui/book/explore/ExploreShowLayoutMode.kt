package io.legado.app.ui.book.explore

internal enum class ExploreShowLayoutMode(val value: Int, val columns: Int) {
    LIST(0, 1),
    GRID_2(2, 2),
    GRID(1, 3);

    companion object {
        fun from(value: Int): ExploreShowLayoutMode {
            return entries.firstOrNull { it.value == value } ?: LIST
        }
    }
}
