package com.scoophub.game.enums

enum class SideEnum {
    WHITE,
    BLACK,
    ;

    fun opposite(): SideEnum = if (this == WHITE) BLACK else WHITE
}
