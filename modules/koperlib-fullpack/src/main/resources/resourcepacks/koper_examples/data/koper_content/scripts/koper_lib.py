# KoperLib Python Shorthand Library
# Massive API Expansion (100+ Functions)

class Koper:
    @staticmethod
    def log(msg): bridge.log(str(msg))
    
    @staticmethod
    def cmd(cmd): bridge.executeCommand(cmd)
    
    # --- Player ---
    @staticmethod
    def msg(msg): bridge.broadcast(msg)
    
    @staticmethod
    def title(p, t="", s="", fi=10, st=70, fo=20): bridge.sendTitle(p, t, s, fi, st, fo)
    
    @staticmethod
    def action(p, msg): bridge.sendActionBar(p, msg)
    
    @staticmethod
    def hp(p, val=None):
        if val is not None: bridge.setHealth(p, float(val))
        else: return bridge.getHealth(p)
        
    @staticmethod
    def gm(p, mode=None):
        if mode is not None: bridge.setGamemode(p, mode)
        else: return bridge.getGamemode(p)

    @staticmethod
    def food(p, val=None):
        if val is not None: bridge.setFoodLevel(p, int(val))
        else: return bridge.getFoodLevel(p)

    # --- World ---
    @staticmethod
    def time(val=None):
        if val is not None: bridge.setTime(int(val))
        else: return bridge.getTime()

    @staticmethod
    def weather(type): bridge.setWeather(type)

    @staticmethod
    def explode(x, y, z, power=4.0, fire=False, interact="destroy"):
        bridge.explode(x, y, z, float(power), fire, interact)

    @staticmethod
    def lightning(x, y, z, eff=False): bridge.strikeLightning(float(x), float(y), float(z), eff)

    @staticmethod
    def set(id, x, y, z): bridge.setBlock(id, int(x), int(y), int(z))

    @staticmethod
    def get(x, y, z): return bridge.getBlock(int(x), int(y), int(z))

    # --- Entity ---
    @staticmethod
    def spawn(id, x, y, z): bridge.spawnEntity(id, float(x), float(y), float(z))

    @staticmethod
    def kill(e): bridge.kill(e)

    @staticmethod
    def vel(e, x, y, z): bridge.setVelocity(e, float(x), float(y), float(z))

    @staticmethod
    def add_vel(e, x, y, z): bridge.addVelocity(e, float(x), float(y), float(z))

    @staticmethod
    def fire(e, t): bridge.setFire(e, int(t))

    @staticmethod
    def glow(e, v): bridge.setGlowing(e, bool(v))

    @staticmethod
    def mount(p, v): bridge.mount(p, v)

    # --- Effects ---
    @staticmethod
    def effect(p, id, d=200, a=0): bridge.applyEffect(p, id, int(d), int(a))

    # --- Inventory ---
    @staticmethod
    def give(p, id, c=1): bridge.giveItem(p, id, int(c))

    @staticmethod
    def hand(p, h="main"): return bridge.getItemInHand(p, h)

    # --- Batch 5: Utility & Math ---
    @staticmethod
    def x(e): return bridge.getX(e)
    @staticmethod
    def y(e): return bridge.getY(e)
    @staticmethod
    def z(e): return bridge.getZ(e)
    
    @staticmethod
    def vx(e): return bridge.getVelX(e)
    @staticmethod
    def vy(e): return bridge.getVelY(e)
    @staticmethod
    def vz(e): return bridge.getVelZ(e)

    @staticmethod
    def dim(e): return bridge.getDimensionId(e)
    
    @staticmethod
    def world_name(e): return bridge.getWorldName(e)

    @staticmethod
    def count(): return bridge.getPlayerCount()

    @staticmethod
    def stop(): bridge.stopServer()

    @staticmethod
    def save(): bridge.saveServer()

    @staticmethod
    def rand(): return bridge.math_random()
    @staticmethod
    def sin(v): return bridge.math_sin(float(v))
    @staticmethod
    def cos(v): return bridge.math_cos(float(v))
    @staticmethod
    def sqrt(v): return bridge.math_sqrt(float(v))
    @staticmethod
    def abs(v): return bridge.math_abs(float(v))
    @staticmethod
    def floor(v): return bridge.math_floor(float(v))
    @staticmethod
    def ceil(v): return bridge.math_ceil(float(v))

    @staticmethod
    def inv(e, v): bridge.setInvulnerable(e, bool(v))

    @staticmethod
    def ground(e): return bridge.isOnGround(e)

    @staticmethod
    def noclip(e, v): bridge.setNoClip(e, bool(v))

    @staticmethod
    def riding(e): return bridge.isRiding(e)

koper = Koper()
