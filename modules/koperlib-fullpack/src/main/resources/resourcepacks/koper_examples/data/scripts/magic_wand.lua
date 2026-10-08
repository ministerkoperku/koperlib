-- KoperLib Example Lua Script: Magic Wand
-- This script is called when the magic wand is used (right-click)
-- Event: ON_USE

-- Print a message to the server console
print("[KoperLib Lua] Magic Wand used! Event: " .. (event or "unknown"))

-- Example: you could add custom logic here
-- The KoperLib engine passes 'event' variable with:
--   ON_USE       - right click
--   ON_BREAK     - block broken  
--   ON_INTERACT  - entity interaction

if event == "ON_USE" then
    print("[KoperLib Lua] Casting spell...")
end
