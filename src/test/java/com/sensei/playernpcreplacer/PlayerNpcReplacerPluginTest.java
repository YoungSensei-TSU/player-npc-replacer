package com.sensei.playernpcreplacer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PlayerNpcReplacerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PlayerNpcReplacerPlugin.class);
		RuneLite.main(args);
	}
}
