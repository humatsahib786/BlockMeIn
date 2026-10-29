package com.blockmein;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Block Me In: press the key (default G, rebindable in Options > Controls > Key Binds)
 * to place blocks on all 4 sides of you (at feet and head height) and on top of you.
 * If you're standing over air, it also puts a block under you so the box is sealed.
 *
 * Blocks are taken from your hotbar (the held block first), exactly as if you'd
 * placed them by hand, so it works in singleplayer and on servers.
 */
public class BlockMeInClient implements ClientModInitializer {
	public static final String MOD_ID = "blockmein";

	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

	private static KeyBinding surroundKey;

	@Override
	public void onInitializeClient() {
		surroundKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.blockmein.surround",   // name shown in the Controls menu
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,            // default key; change it in-game
				CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean pressed = false;
			while (surroundKey.wasPressed()) {
				pressed = true; // several presses in one tick count as one
			}
			if (pressed) {
				boxIn(client);
			}
		});
	}

	private static void boxIn(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null || client.interactionManager == null
				|| client.getNetworkHandler() == null || player.isSpectator()) {
			return;
		}

		// 1) Snap to the middle of the block you're standing in, so the side walls
		//    don't overlap your hitbox (a block can't be placed inside a player).
		double centerX = MathHelper.floor(player.getX()) + 0.5;
		double centerZ = MathHelper.floor(player.getZ()) + 0.5;
		if (player.getX() != centerX || player.getZ() != centerZ) {
			player.setVelocity(0.0, player.getVelocity().y, 0.0);
			player.setPosition(centerX, player.getY(), centerZ);
			client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
					centerX, player.getY(), centerZ, player.isOnGround(), player.horizontalCollision));
		}

		// 2) Work out which spots need a block.
		List<BlockPos> targets = computeTargets(world, player);
		if (targets.isEmpty()) {
			client.inGameHud.setOverlayMessage(Text.translatable("blockmein.message.already"), false);
			return;
		}

		// 3) Place them, switching hotbar slots as needed, then switch back.
		PlayerInventory inventory = player.getInventory();
		int originalSlot = inventory.getSelectedSlot();
		int placed = 0;
		boolean ranOut = false;

		for (BlockPos pos : targets) {
			if (!world.getBlockState(pos).isReplaceable()) {
				continue; // something is already there
			}

			int slot = findBlockSlot(inventory, world, pos);
			if (slot == -1) {
				ranOut = true;
				break;
			}
			inventory.setSelectedSlot(slot);

			// Click "into" the empty spot itself. Because air/water/grass are
			// replaceable, the block goes exactly there. Clicking the spot rather
			// than a neighbouring block also means we never open a chest or door.
			BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
			ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
			if (result.isAccepted()) {
				placed++;
			}
		}

		inventory.setSelectedSlot(originalSlot);

		if (placed > 0) {
			player.swingHand(Hand.MAIN_HAND);
		}
		if (ranOut) {
			client.inGameHud.setOverlayMessage(Text.translatable("blockmein.message.no_blocks"), false);
		} else {
			client.inGameHud.setOverlayMessage(Text.translatable("blockmein.message.done", placed), false);
		}
	}

	/**
	 * Floor (only if you're over air), then the 4 sides at every height your body
	 * occupies (normally feet + head), then the block above your head.
	 */
	private static List<BlockPos> computeTargets(ClientWorld world, ClientPlayerEntity player) {
		Box box = player.getBoundingBox();
		int x = MathHelper.floor(player.getX());
		int z = MathHelper.floor(player.getZ());
		int minY = MathHelper.floor(box.minY + 1.0E-4);
		int maxY = MathHelper.floor(box.maxY - 1.0E-4);

		List<BlockPos> targets = new ArrayList<>();

		BlockPos feet = new BlockPos(x, minY, z);
		BlockPos below = feet.down();
		if (world.getBlockState(feet).isReplaceable() && world.getBlockState(below).isReplaceable()) {
			targets.add(below);
		}

		for (int y = minY; y <= maxY; y++) {
			BlockPos level = new BlockPos(x, y, z);
			for (Direction dir : Direction.Type.HORIZONTAL) {
				targets.add(level.offset(dir));
			}
		}

		targets.add(new BlockPos(x, maxY + 1, z));

		targets.removeIf(pos -> !world.getBlockState(pos).isReplaceable());
		return targets;
	}

	/** Held item first, then the rest of the hotbar. Returns -1 if nothing usable. */
	private static int findBlockSlot(PlayerInventory inventory, ClientWorld world, BlockPos pos) {
		int selected = inventory.getSelectedSlot();
		if (isGoodWallBlock(inventory.getStack(selected), world, pos)) {
			return selected;
		}
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			if (isGoodWallBlock(inventory.getStack(i), world, pos)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * A solid, full-size block. Skips sand/gravel/concrete powder/anvils (they'd fall
	 * on you), and chests/shulker boxes/furnaces etc. (so you don't waste them).
	 */
	private static boolean isGoodWallBlock(ItemStack stack, ClientWorld world, BlockPos pos) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		if (block instanceof FallingBlock || block instanceof BlockEntityProvider) {
			return false;
		}
		return block.getDefaultState().isFullCube(world, pos);
	}
}
