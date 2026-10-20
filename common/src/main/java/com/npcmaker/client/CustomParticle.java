package com.npcmaker.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * A custom (uploaded) particle: drifts gently up, plays through its frames over its life (a tall PNG is split into
 * square frames), and fades out at the end. Shown with transparency, so soft-edged textures look right.
 */
public class CustomParticle extends SingleQuadParticle {

    private final SpriteSet sprites;

    protected CustomParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, xd, yd, zd, sprites.first());
        this.sprites = sprites;
        this.xd = xd * 0.3 + (random.nextDouble() - 0.5) * 0.02;
        this.yd = yd * 0.3 + 0.01 + random.nextDouble() * 0.02;
        this.zd = zd * 0.3 + (random.nextDouble() - 0.5) * 0.02;
        this.lifetime = 24 + random.nextInt(16);
        this.gravity = 0.0F;
        this.friction = 0.96F;
        this.quadSize *= 1.2F;
        this.hasPhysics = false;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        float left = (lifetime - age) / 8.0F;
        setAlpha(Math.max(0.0F, Math.min(1.0F, left)));
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xd, double yd, double zd, RandomSource random) {
            return new CustomParticle(level, x, y, z, xd, yd, zd, sprites);
        }
    }
}
