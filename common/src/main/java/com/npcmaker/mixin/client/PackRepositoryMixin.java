package com.npcmaker.mixin.client;

import com.npcmaker.client.ClientCustom;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds NPC Maker's custom content pack (sounds, particles and models from the server) to the game's resource pack
 * list. Only the client's resource pack list gets it (the one built with vanilla's client pack source), not data packs.
 */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {

    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void npcmaker$addCustomPack(RepositorySource[] given, CallbackInfo ci) {
        if (sources.stream().anyMatch(s -> s instanceof ClientPackSource)) {
            Set<RepositorySource> more = new LinkedHashSet<>(sources);
            more.add(ClientCustom.SOURCE);
            sources = more;
        }
    }
}
