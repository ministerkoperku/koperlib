-- KoperLib Example Lua Script: Bounce Pad
-- This script is called when the bounce pad block is used (right-click)
-- Event: ON_USE

print("[KoperLib Lua] Bounce Pad activated!")

if event == "ON_USE" then
    print("[KoperLib Lua] Player bounced on pad!")
    -- In full implementation, this would apply velocity to the player
end
