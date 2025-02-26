// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit;

import com.intellij.ui.IconManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * NOTE THIS FILE IS AUTO-GENERATED
 * DO NOT EDIT IT BY HAND, run "Generate icon classes" configuration instead
 */
public final class DevKitIcons {
  private static @NotNull Icon load(@NotNull String path, @NotNull String expUIPath, int cacheKey, int flags) {
    return IconManager.getInstance().loadRasterizedIcon(path, expUIPath, DevKitIcons.class.getClassLoader(), cacheKey, flags);
  }
  /** 16x16 */ public static final @NotNull Icon Add_sdk = load("icons/add_sdk.svg", "icons/expui/addSDK.svg", 641117830, 2);

  public static final class Gutter {
    /** 12x12 */ public static final @NotNull Icon DescriptionFile = load("icons/gutter/descriptionFile.svg", "icons/expui/gutter/descriptionFile@14x14.svg", 1318760137, 2);
    /** 12x12 */ public static final @NotNull Icon Diff = load("icons/gutter/diff.svg", "icons/expui/gutter/diff@14x14.svg", 124039984, 2);
    /** 12x12 */ public static final @NotNull Icon Plugin = load("icons/gutter/plugin.svg", "icons/expui/gutter/plugin@14x14.svg", 1850322899, 2);
    /** 12x12 */ public static final @NotNull Icon Properties = load("icons/gutter/properties.svg", "icons/expui/gutter/properties@14x14.svg", -818710709, 2);
  }

  /** 16x16 */ public static final @NotNull Icon RemoteMapping = load("icons/remoteMapping.svg", "icons/expui/remoteMapping.svg", 1371307852, 2);
  /** 16x16 */ public static final @NotNull Icon Sdk_closed = load("icons/sdk_closed.svg", "icons/expui/sdkClosed.svg", -1355048140, 2);
}
