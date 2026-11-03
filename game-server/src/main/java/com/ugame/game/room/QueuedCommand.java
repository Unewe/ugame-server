package com.ugame.game.room;

import com.ugame.protocol.InputPacket;

/** Команда в очереди бойца и тик, когда она пришла (ожидание в очереди учитывается в отмотке). */
record QueuedCommand(InputPacket.Command cmd, long receivedTick) {}
