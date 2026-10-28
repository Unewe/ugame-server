package com.ugame.game.room;

/**
 * @param tickRate         тиков симуляции в секунду
 * @param snapshotEvery    слать снапшот каждые N тиков (1 = каждый тик)
 * @param matchDurationSec длительность матча
 * @param joinTimeoutSec   сколько ждать всех игроков; после — старт с теми, кто пришёл
 * @param threads          потоков для тиков комнат (обычно = числу ядер)
 */
public record RoomSettings(int tickRate, int snapshotEvery, int matchDurationSec, int joinTimeoutSec, int threads) {}
