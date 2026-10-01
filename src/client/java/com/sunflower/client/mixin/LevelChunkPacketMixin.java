package com.sunflower.client.mixin;

import com.sunflower.client.rt.RtBoot;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bulk-обновление объема при полной замене данных чанка (загрузка/ресинк чанка).
 *
 * <p>Здесь воксели не пишутся поштучно — в очередь на перепек встаёт весь столбец
 * секций чанка. Причина: пакет приходит пачкой, а обходить 4096 блоков на клиенте
 * дорого. Перепек бюджетный (drain по нескольку секций за кадр), а незалитое
 * пространство читается как воздух (fail-open): луч лишний свет вместо фантомной тени.
 */
@Mixin(LevelChunk.class)
public class LevelChunkPacketMixin {
    @Inject(
            method = "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)V",
            at = @At("RETURN"))
    private void sunflower$markColumnDirty(CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        try {
            RtBoot.onChunkDataReplaced(self.getPos().x(), self.getPos().z());
        } catch (Throwable t) {
            // Инвалидация не должна ронять загрузку чанка: максимум — секции
            // перепекутся позже при recenter, теней будет чуть меньше.
        }
    }
}