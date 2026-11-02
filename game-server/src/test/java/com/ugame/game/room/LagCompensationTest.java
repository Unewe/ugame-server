package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import com.ugame.protocol.SnapshotPacket;
import org.junit.jupiter.api.Test;

/**
 * Стрелок (команда 0) стоит в (10, 2) и стреляет вверх. Цель (команда 1) стоит в (10, 6) и в тот же
 * тик начинает уходить вправо. Снаряд долетает за ~5 тиков — цель к этому времени на ~0,8 м правее,
 * то есть без компенсации это промах. Стрелок с пингом видел её ещё на месте.
 */
class LagCompensationTest {
    private static final int TICK_RATE = 30;
    private static final float UP = (float) (Math.PI / 2);

    private World w;
    private World.Brawler shooter;
    private World.Brawler target;
    private int shooterSeq;
    private int targetSeq;

    private void setUp() {
        w = new World(TICK_RATE, 3, 300);
        shooter = w.spawnBrawler(1, 0);
        target = w.spawnBrawler(2, 1);
        shooter.x = 10f;
        shooter.y = 2f;
        target.x = 10f;
        target.y = 6f;
        steps(20); // цель стоит — история заполняется
    }

    private void shooterInput(float aim, int buttons) {
        w.applyInput(shooter, new InputPacket.Command(++shooterSeq & 0xFFFF, 0, 0, Quantize.angleToU16(aim), buttons));
    }

    private void targetMoves(int moveX) {
        w.applyInput(target, new InputPacket.Command(++targetSeq & 0xFFFF, moveX, 0, Quantize.angleToU16(-UP), 0));
    }

    /** Тики; клиент шлёт по команде за тик, поэтому seq стрелка растёт вместе с ними. */
    private void steps(int n) {
        for (int i = 0; i < n; i++) {
            w.step();
            shooterSeq++;
        }
    }

    private void shootWhileTargetLeaves() {
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        targetMoves(127);
        steps(20);
    }

    @Test
    void withoutRttTheMovingTargetIsMissed() {
        setUp();
        shootWhileTargetLeaves();
        assertEquals(World.BRAWLER_MAX_HP, target.hp, "без отмотки цель успевает уйти");
    }

    @Test
    void laggingShooterHitsWhereHeSawTheTarget() {
        setUp();
        w.setRtt(shooter, 200); // 6 тиков RTT + 3 тика интерполяции
        assertEquals(9f, w.rewindTicks(shooter), 1e-4);
        shootWhileTargetLeaves();
        assertEquals(World.BRAWLER_MAX_HP - World.PROJECTILE_DAMAGE, target.hp);
        assertEquals(World.PROJECTILE_DAMAGE, shooter.superCharge, "попадание в прошлом заряжает супер как обычно");
    }

    @Test
    void lagCompensatedShotMissesWhereTheTargetIsNow() {
        setUp();
        // Цель ушла давно, сейчас стоит на линии огня, а стрелок с пингом ещё видит её в старом месте.
        target.x = 13f;
        steps(20);
        target.x = 10f; // «телепорт» прямо перед выстрелом: в истории цели на линии огня нет
        w.setRtt(shooter, 200);
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        steps(20);
        assertEquals(World.BRAWLER_MAX_HP, target.hp, "проверяется то, что видел стрелок, а не текущая позиция");
    }

    @Test
    void rewindIsCappedAndOffWithoutRtt() {
        w = new World(TICK_RATE, 3, 300);
        World.Brawler b = w.spawnBrawler(1, 0);
        assertEquals(0f, w.rewindTicks(b), "RTT ещё не измерен — без отмотки");
        w.setRtt(b, 0);
        assertEquals(3f, w.rewindTicks(b), 1e-4, "пинг 0 — остаётся задержка интерполяции");
        w.setRtt(b, 5000);
        assertEquals(9f, w.rewindTicks(b), 1e-4, "не больше 300 мс");

        World off = new World(TICK_RATE, 3, 0);
        World.Brawler o = off.spawnBrawler(1, 0);
        off.setRtt(o, 200);
        assertEquals(0f, off.rewindTicks(o), "max-rewind-ms=0 выключает компенсацию");
    }

    @Test
    void targetDashingInThePastIsNotHit() {
        setUp();
        target.dashTicksLeft = 1000; // «рывок на месте», чтобы история записала неуязвимость
        target.dashDx = 0;
        target.dashDy = 0;
        steps(20);
        w.setRtt(shooter, 200);

        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        target.dashTicksLeft = 0; // сейчас цель уже не в рывке, но стрелок видел её в рывке
        steps(20);
        assertEquals(World.BRAWLER_MAX_HP, target.hp);
    }

    @Test
    void targetDashingNowIsNotHitEvenIfShooterSawItStanding() {
        setUp();
        w.setRtt(shooter, 200);
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        target.dashTicksLeft = 1000; // увернулся только что — рывок честен для уклоняющегося
        target.dashDx = 0;
        target.dashDy = 0;
        steps(20);
        assertEquals(World.BRAWLER_MAX_HP, target.hp);
    }

    @Test
    void targetDeadInTheRewoundMomentIsNotHit() {
        setUp();
        target.hp = 1;
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        steps(20);
        assertFalse(target.alive, "первый снаряд убивает");
        while (!target.alive) {
            steps(1); // ждём возрождения
        }
        // Сразу после возрождения ставим цель на линию огня. Стрелок с пингом в этот момент
        // ещё видит её мёртвой — значит, попасть не мог.
        target.x = 10f;
        target.y = 6f;
        w.setRtt(shooter, 200);
        int deaths = target.deaths;
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        steps(8);
        assertEquals(World.BRAWLER_MAX_HP, target.hp);
        assertEquals(deaths, target.deaths);
    }

    @Test
    void attackCooldownCountsClientCommands() {
        setUp();
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        int fired = w.ownState(shooter).lastAttackSeq();
        assertEquals(shooterSeq & 0xFFFF, fired);
        steps(30); // тиков сервера прошло много — решают номера команд

        shooterSeq = fired + 13; // следующая команда — через 14 после выстрела: рано
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        assertEquals(fired, w.ownState(shooter).lastAttackSeq(), "сервер не выпустил ранний выстрел");

        shooterSeq = fired + 14; // через 15 команд — тот выстрел, который клиент уже показал
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        assertEquals((fired + 15) & 0xFFFF, w.ownState(shooter).lastAttackSeq());
    }

    @Test
    void burstOfCommandsCannotFireFasterThanServerAllows() {
        setUp();
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        int fired = w.ownState(shooter).lastAttackSeq();
        shooterSeq += 14; // «ускоренный» клиент: 15 команд за один тик сервера
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        assertEquals(fired, w.ownState(shooter).lastAttackSeq(), "второй выстрел через тик не проходит");
        assertEquals(1, projectiles());
    }

    @Test
    void projectileCarriesOwnerIdInSnapshot() {
        setUp();
        shooterInput(UP, InputPacket.Buttons.ATTACK);
        w.step();
        var shot = w.snapshotEntities().stream()
                .filter(e -> e.kind() == SnapshotPacket.EntityKind.PROJECTILE).findFirst().orElseThrow();
        assertEquals(shooter.id, shot.hp());
        assertTrue(shot.id() >= World.FIRST_PROJECTILE_ID, "id снарядов не пересекаются с id бойцов");
    }

    private long projectiles() {
        return w.snapshotEntities().stream().filter(e -> e.kind() == SnapshotPacket.EntityKind.PROJECTILE).count();
    }
}
