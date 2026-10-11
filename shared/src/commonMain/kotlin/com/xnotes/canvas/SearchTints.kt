package com.xnotes.canvas

import com.xnotes.core.pal.Renderer

/** A search's matches tinted on the pages, drawn by the canvas under the selections (the host lays them out). */
interface SearchTints {
    fun draw(r: Renderer)
}
