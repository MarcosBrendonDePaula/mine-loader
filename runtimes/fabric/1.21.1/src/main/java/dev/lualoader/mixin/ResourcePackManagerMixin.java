package dev.lualoader.mixin;

import dev.lualoader.resources.GeneratedResourcePackProvider;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
    @Shadow @Final @Mutable private Set<ResourcePackProvider> providers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void luaLoader$addGeneratedProvider(ResourcePackProvider[] initialProviders, CallbackInfo ci) {
        Set<ResourcePackProvider> mutableProviders = new HashSet<>(providers);
        mutableProviders.add(new GeneratedResourcePackProvider());
        providers = mutableProviders;
    }
}
