package com.scoophub.game.dto

data class CreateRoomCommand(
    val gameId: String,
    val baseMs: Long,
    val byoYomiMs: Long,
    val byoYomiCount: Int,
    val sidePreference: String,
    val guestId: String,
    val nickname: String,
    val isSetupRequired: Boolean,
    val ip: String,
)
