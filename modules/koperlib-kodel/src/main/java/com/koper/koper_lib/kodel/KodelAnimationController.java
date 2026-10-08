package com.koper.koper_lib.kodel;

/**
 * Single-clip playback head for a {@link KodelAnimation}. Ticks in seconds,
 * folds time into the clip domain (loop / hold / play-once), and hands the
 * ready time to {@link KodelSampler}. Kept deliberately small — one head per
 * animated object, an entity with several clips owns several heads.
 */
public final class KodelAnimationController {
    public enum Loop {
        LOOP,
        ONCE,
        HOLD
    }

    public enum State {
        PLAYING,
        PAUSED,
        STOPPED
    }

    private KodelAnimation animation;
    private float time;
    private float speed = 1f;
    private Loop loop = Loop.LOOP;
    private State state = State.STOPPED;
    private boolean reverse;

    public KodelAnimationController() {}

    /** Starts playback of a clip, restarting from zero. */
    public void play(KodelAnimation anim) {
        this.animation = anim;
        this.time = 0;
        this.state = anim.length > 0 ? State.PLAYING : State.STOPPED;
    }

    public void pause() {
        if (state == State.PLAYING) state = State.PAUSED;
    }

    public void resume() {
        if (state == State.PAUSED) state = State.PLAYING;
    }

    public void stop() {
        state = State.STOPPED;
        time = 0;
    }

    public void seek(float seconds) {
        time = Math.max(0, seconds);
        if (animation != null) time = fold(time);
    }

    public KodelAnimation animation() {
        return animation;
    }

    public State state() {
        return state;
    }

    /** Advances the head; returns true while the clip is still playing. */
    public boolean tick(float deltaSeconds) {
        if (state != State.PLAYING || animation == null || animation.length <= 0) {
            return state == State.PLAYING;
        }
        float d = deltaSeconds * speed * (reverse ? -1f : 1f);
        time += d;
        if (d < 0) {
            if (time < 0) {
                if (loop == Loop.LOOP) {
                    time = animation.length - (animation.length == 0 ? 0 : ((-time) % animation.length));
                } else {
                    time = 0;
                    state = State.STOPPED;
                }
            }
        } else {
            time = fold(time);
        }
        return state == State.PLAYING;
    }

    /** Time folded into [0, length) for LOOP, clamped by ONCE/HOLD, ready for sampling. */
    public float currentTime() {
        if (animation == null) return 0;
        return fold(time);
    }

    private float fold(float t) {
        if (animation == null || animation.length <= 0) return 0;
        switch (loop) {
            case LOOP:
                return ((t % animation.length) + animation.length) % animation.length;
            case ONCE:
                if (t >= animation.length) {
                    state = State.STOPPED;
                    return animation.length;
                }
                return Math.max(0, t);
            case HOLD:
            default:
                return Math.max(0, Math.min(animation.length, t));
        }
    }

    public float speed() {
        return speed;
    }

    public void speed(float speed) {
        this.speed = speed;
    }

    public boolean reverse() {
        return reverse;
    }

    public void reverse(boolean reverse) {
        this.reverse = reverse;
    }

    public Loop loop() {
        return loop;
    }

    public void loop(Loop loop) {
        this.loop = loop;
    }
}