-- KoperLib Lua Shorthand Library
-- Massive API Expansion (100+ Functions)

koper = {}

-- --- Utility ---
function koper.log(msg) bridge:log(tostring(msg)) end
function koper.cmd(cmd) bridge:executeCommand(cmd) end
function koper.wait(ticks) bridge:waitTicks(ticks) end -- Placeholder if implemented

-- --- Player ---
function koper.msg(msg) bridge:broadcast(msg) end
function koper.title(p, t, s, fi, st, fo) bridge:sendTitle(p, t or "", s or "", fi or 10, st or 70, fo or 20) end
function koper.action(p, msg) bridge:sendActionBar(p, msg) end
function koper.hp(p, val) if val then bridge:setHealth(p, val) else return bridge:getHealth(p) end end
function koper.gm(p, mode) if mode then bridge:setGamemode(p, mode) else return bridge:getGamemode(p) end end
function koper.food(p, val) if val then bridge:setFoodLevel(p, val) else return bridge:getFoodLevel(p) end end
function koper.xp(p, val) if val then bridge:setExperience(p, val) else return bridge:getExperience(p) end end
function koper.inv_clear(p) bridge:clearInventory(p) end

-- --- World ---
function koper.time(val) if val then bridge:setTime(val) else return bridge:getTime() end end
function koper.weather(type) bridge:setWeather(type) end
function koper.explode(x, y, z, p, f, i) bridge:explode(x, y, z, p or 4, f or false, i or "destroy") end
function koper.lightning(x, y, z, eff) bridge:strikeLightning(x, y, z, eff or false) end
function koper.set(id, x, y, z) bridge:setBlock(id, x, y, z) end
function koper.get(x, y, z) return bridge:getBlock(x, y, z) end
function koper.is_air(x, y, z) return bridge:isAir(x, y, z) end

-- --- Entity ---
function koper.spawn(id, x, y, z) bridge:spawnEntity(id, x, y, z) end
function koper.kill(e) bridge:kill(e) end
function koper.heal(e, v) bridge:heal(e, v) end
function koper.vel(e, x, y, z) bridge:setVelocity(e, x, y, z) end
function koper.add_vel(e, x, y, z) bridge:addVelocity(e, x, y, z) end
function koper.fire(e, t) bridge:setFire(e, t) end
function koper.ext(e) bridge:extinguish(e) end
function koper.name(e, n) if n then bridge:setCustomName(e, n) else return bridge:getName(e) end end
function koper.gravity(e, v) bridge:setGravity(e, v) end
function koper.glow(e, v) bridge:setGlowing(e, v) end
function koper.inv(e, v) bridge:setInvulnerable(e, v) end
function koper.mount(p, v) bridge:mount(p, v) end
function koper.dismount(e) bridge:dismount(e) end

-- --- Effects & Attributes ---
function koper.effect(p, id, d, a) bridge:applyEffect(p, id, d or 200, a or 0) end
function koper.rem_effect(p, id) bridge:removeEffect(p, id) end
function koper.attr(e, id) return bridge:getAttribute(e, id) end
function koper.step(e, v) bridge:setStepHeight(e, v) end

-- --- Inventory ---
function koper.give(p, id, c) bridge:giveItem(p, id, c or 1) end
function koper.drop(x, y, z, id, c) bridge:dropItem(x, y, z, id, c or 1) end
function koper.hand(p, h) return bridge:getItemInHand(p, h or "main") end
function koper.damage(s, a, p) bridge:damageItem(s, a, p) end

-- --- Math & Misc ---
function koper.dist(x1, y1, z1, x2, y2, z2) return bridge:getDistance(x1, y1, z1, x2, y2, z2) end
function koper.pos(e) return {e:getX(), e:getY(), e:getZ()} end
function koper.rot(e) return bridge:getLookVector(e) end

-- More to follow (batching to 100)
