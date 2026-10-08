package com.koper.koper_lib.api.core;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

// lets fullpack hand a mob to kodel for drawing without knowing kodel exists. kodel installs it
// on the client; handles() is false when no .kodel by that name is in any enabled pack
public final class KodelEntityBridge {

    public record Clips(String idle, String walk, String attack, String death) {}

    public interface Provider {
        boolean handles(String model);

        <T extends Mob> EntityRendererProvider<T> renderer(String model, Identifier texture, Clips clips, float shadow);
    }

    private static volatile Provider provider;

    private KodelEntityBridge() {}

    public static void install(Provider value) {
        provider = value;
    }

    public static Provider get() {
        return provider;
    }
}
