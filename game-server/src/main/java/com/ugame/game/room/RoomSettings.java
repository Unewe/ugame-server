package com.ugame.game.room;

/**
 * @param tickRate         тиков симуляции в секунду
 * @param snapshotEvery    слать снапшот каждые N тиков (1 = каждый тик)
 * @param matchDurationSec длительность матча
 * @param joinTimeoutSec   сколько ждать всех игроков; после — старт с теми, кто пришёл
 * @param threads          потоков для тиков комнат (обычно = числу ядер)
 * @param interpDelayTicks компенсация лагов: на сколько тиков клиент показывает чужих в прошлом
 *                         (должно совпадать с MatchView.InterpDelayTicks)
 * @param maxRewindMs      компенсация лагов: предел отмотки при проверке попаданий; 0 — выключена
 */
public record RoomSettings(int tickRate, int snapshotEvery, int matchDurationSec, int joinTimeoutSec, int threads,
                           int interpDelayTicks, int maxRewindMs) {}
