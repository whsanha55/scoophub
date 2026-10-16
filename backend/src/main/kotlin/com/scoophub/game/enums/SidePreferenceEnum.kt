package com.scoophub.game.enums

import kotlin.random.Random

enum class SidePreferenceEnum {
    WHITE,
    BLACK,
    RANDOM,
    ;

    /** 방장 진영을 정한다. 무작위면 이때 정한다. */
    fun pickSide(random: Random): SideEnum = when (this) {
        WHITE -> SideEnum.WHITE
        BLACK -> SideEnum.BLACK
        RANDOM -> if (random.nextBoolean()) SideEnum.WHITE else SideEnum.BLACK
    }

    companion object {
        /** 요청 값(대소문자 무관)을 해석한다. 모르는 값은 무작위로 본다. */
        fun from(value: String): SidePreferenceEnum =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RANDOM
    }
}
