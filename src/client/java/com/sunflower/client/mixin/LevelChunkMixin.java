package com.sunflower.client.mixin;

import com.sunflower.client.rt.RtBoot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Адаптивная инвалидация вокселей: поставлен/сломан блок — воксель пишется
 * МГНОВЕННО (без очереди и без перепека), тени обновляются в том же кадре.
 *
 * <p>Почему хук именно здесь, а не на ClientLevel.setBlock (как было):
 * проверено байткодом 26.2 — ВСЕ пути правки блока в итоге сводятся к
 * {@code LevelChunk.setBlockState}, но НЕ все проходят через ClientLevel.setBlock:
 * <ul>
 *   <li>предикт клиента: {@code ClientLevel.setBlock} -> {@code Level.setBlock};</li>
 *   <li>подтверждение сервера: {@code ClientLevel.setServerVerifiedBlockState} ->
 *       {@code invokespecial Level.setBlock} — мимо ClientLevel.setBlock полностью
 *       (это и был баг: тени отставали навсегда, т.к. блок менялся, а воксель — нет);</li>
 *   <li>все серверные обновления идут через тот же Level.setBlock.</li>
 * </ul>
 * Level.setBlock всегда доходит до LevelChunk.setBlockState, поэтому хук в чанке
 * покрывает и предикт, и сервер, и локальные правки — дёшево и полно.
 *
 * <p>Возвращаемое значение — StateChange (null = ничего не изменилось), по нему
 * отсекаем no-op вызовы (тот же блок): дешевле, чем сравнивать State.
 *
 * <p>Инвалидация вокселей никогда не должна ронять игровой цикл — всё под try/catch.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void sunflower$voxelUpdate(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            RtBoot.onBlockStateChanged(pos, state);
        }
    }
}