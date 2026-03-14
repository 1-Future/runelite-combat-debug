package com.openscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("combatdebug")
public interface CombatDebugConfig extends Config
{
	@ConfigItem(
		keyName = "chatLog",
		name = "Chat Log",
		description = "Print debug info to game chat (also always logs to RuneLite log)"
	)
	default boolean chatLog()
	{
		return true;
	}
}
