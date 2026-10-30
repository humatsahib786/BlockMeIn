package com.blockmein;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
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
 * to wall yourself in: 4 sides at feet + head height, plus the block above your head
 * (and the floor if you're standing over air).
 *
 * Placement is done like a real player, all within a single tick:
 *  - every block is placed by right-clicking a face of a real, existing block next to
 *    the spot (never "into thin air"),
 *  - before each click the player turns to look at that exact face,
 *  - spots that already have a block are skipped,
 *  - END STONE is used first if you have any anywhere in your inventory; otherwise any
 *    other full block. Blocks in the main inventory are swapped into the hotbar (the same
 *    click as pressing a number key over a slot) and swapped back afterwards,
 *  - you swing your arm for each block.
 * Afterwards your rotation, selected hotbar slot and inventory layout are put back.
 *
 * It does not move you: stand in the middle of the block yourself.
 */
public class BlockMeInClient implements ClientModInitializer {
	public static final String MOD_ID = "blockmein";

	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

	/** Order we look for something to click on: below first, then the sides, then above. */
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
	};

	private static final int HOTBAR_SIZE = 9;
	private static final int INVENTORY_SIZE = 36; // hotbar (0-8) + main inventory (9-35)

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

	// ------------------------------------------------------------------------------------

	/** A face of an existing block that we can right-click to fill the spot next to it. */
	private record Support(BlockPos block, Direction face) {}

	private enum Outcome { PLACED, FAILED, NO_BLOCKS }

	/** Everything one key press needs, so the helpers stay short. */
	private static final class Session {
		final MinecraftClient client;
		final ClientPlayerEntity player;
		final ClientWorld world;
		final PlayerInventory inventory;
		final int originalSlot;
		/** Inventory -> hotbar swaps we did, so we can undo them afterwards. */
		final List<int[]> swaps = new ArrayList<>();
		int workSlot = -1;
		int placed = 0;
		boolean ranOut = false;
		boolean rotated = false;

		Session(MinecraftClient client) {
			this.client = client;
			this.player = client.player;
			this.world = client.world;
			this.inventory = client.player.getInventory();
			this.originalSlot = this.inventory.getSelectedSlot();
		}
	}

	private static void boxIn(MinecraftClient client) {
		if (client.player == null || client.world == null || client.interactionManager == null
				|| client.getNetworkHandler() == null || client.player.isSpectator()) {
			return;
		}

		Session s = new Session(client);

		List<BlockPos> remaining = computeTargets(s.world, s.player);
		if (remaining.isEmpty()) {
			client.inGameHud.setOverlayMessage(Text.translatable("blockmein.message.already"), false);
			return;
		}

		float realYaw = s.player.getYaw();
		float realPitch = s.player.getPitch();

		// Keep going while we make progress: a block placed in one pass can be the thing
		// we click on in the next pass (e.g. feet-level wall -> head-level wall on top).
		for (int round = 0; round < 6 && !remaining.isEmpty() && !s.ranOut; round++) {
			boolean progress = placePass(s, remaining);
			if (!progress && !s.ranOut) {
				// Nothing to click on for the rest (usually the block above your head).
				// Put one "helper" block next to it first, then try again.
				if (!placeHelper(s, remaining)) {
					break;
				}
			}
		}

		// Put everything back the way it was: undo inventory swaps (newest first),
		// then the selected slot and where you were looking.
		for (int i = s.swaps.size() - 1; i >= 0; i--) {
			int[] sw = s.swaps.get(i);
			swap(s, sw[0], sw[1]);
		}
		s.inventory.setSelectedSlot(s.originalSlot);
		s.player.setYaw(realYaw);
		s.player.setPitch(realPitch);
		if (s.rotated) {
			sendLook(s, realYaw, realPitch);
		}

		Text message;
		if (s.ranOut) {
			message = Text.translatable("blockmein.message.no_blocks");
		} else if (!remaining.isEmpty()) {
			message = Text.translatable("blockmein.message.partial", s.placed, remaining.size());
		} else {
			message = Text.translatable("blockmein.message.done", s.placed);
		}
		client.inGameHud.setOverlayMessage(message, false);
	}

	/** One pass over the remaining spots. Returns true if at least one block was placed. */
	private static boolean placePass(Session s, List<BlockPos> remaining) {
		boolean progress = false;
		Iterator<BlockPos> it = remaining.iterator();
		while (it.hasNext()) {
			BlockPos target = it.next();
			if (!s.world.getBlockState(target).isReplaceable()) {
				it.remove(); // already filled -> skip
				continue;
			}
			Support support = findSupport(s.world, target);
			if (support == null) {
				continue; // maybe next pass
			}
			Outcome outcome = place(s, target, support);
			if (outcome == Outcome.NO_BLOCKS) {
				return progress;
			}
			if (outcome == Outcome.PLACED) {
				progress = true;
				it.remove();
			} else {
				it.remove(); // the game refused it (e.g. an entity is in the way); don't retry
			}
		}
		return progress;
	}

	/**
	 * For a spot with nothing next to it to click on, place one extra block beside it
	 * that *can* be supported. For the roof this lands on top of a head-level wall block.
	 */
	private static boolean placeHelper(Session s, List<BlockPos> remaining) {
		for (BlockPos target : remaining) {
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos helper = target.offset(dir);
				if (!s.world.getBlockState(helper).isReplaceable()) {
					continue;
				}
				Support support = findSupport(s.world, helper);
				if (support == null) {
					continue;
				}
				Outcome outcome = place(s, helper, support);
				if (outcome == Outcome.NO_BLOCKS) {
					return false;
				}
				if (outcome == Outcome.PLACED) {
					return true;
				}
			}
		}
		return false;
	}

	/** Right-click the support face, exactly like a player would. */
	private static Outcome place(Session s, BlockPos target, Support support) {
		int slot = selectBlock(s, target);
		if (slot == -1) {
			s.ranOut = true;
			return Outcome.NO_BLOCKS;
		}
		s.inventory.setSelectedSlot(slot);

		// Middle of the face we're clicking.
		Direction face = support.face();
		Vec3d hitPos = Vec3d.ofCenter(support.block()).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);

		// Look at it (both on our screen and as the server sees it).
		Vec3d eye = s.player.getEyePos();
		double dx = hitPos.x - eye.x;
		double dy = hitPos.y - eye.y;
		double dz = hitPos.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90.0F, 90.0F);
		s.player.setYaw(yaw);
		s.player.setPitch(pitch);
		sendLook(s, yaw, pitch);
		s.rotated = true;

		BlockHitResult hit = new BlockHitResult(hitPos, face, support.block(), false);
		ActionResult result = s.client.interactionManager.interactBlock(s.player, Hand.MAIN_HAND, hit);
		if (result.isAccepted()) {
			s.player.swingHand(Hand.MAIN_HAND);
			s.placed++;
			return Outcome.PLACED;
		}
		return Outcome.FAILED;
	}

	private static void sendLook(Session s, float yaw, float pitch) {
		s.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
				yaw, pitch, s.player.isOnGround(), s.player.horizontalCollision));
	}

	/**
	 * Find an existing block next to {@code target} whose face we can click to place into
	 * {@code target}. Skips blocks that would do something when right-clicked (chests,
	 * doors, crafting tables...), so we never open or toggle anything by accident.
	 */
	private static Support findSupport(ClientWorld world, BlockPos target) {
		for (Direction dir : SUPPORT_ORDER) {
			BlockPos neighbor = target.offset(dir);
			BlockState state = world.getBlockState(neighbor);
			if (!state.isReplaceable() && isSafeToClick(state)) {
				return new Support(neighbor, dir.getOpposite());
			}
		}
		return null;
	}

	private static boolean isSafeToClick(BlockState state) {
		Block b = state.getBlock();
		return !(b instanceof BlockEntityProvider       // chests, furnaces, signs, beds, shulkers...
				|| b instanceof DoorBlock
				|| b instanceof TrapdoorBlock
				|| b instanceof FenceGateBlock
				|| b instanceof ButtonBlock
				|| b instanceof LeverBlock
				|| b instanceof CraftingTableBlock  // also smithing/cartography/fletching tables
				|| b instanceof LoomBlock
				|| b instanceof StonecutterBlock
				|| b instanceof GrindstoneBlock
				|| b instanceof AnvilBlock
				|| b instanceof NoteBlock
				|| b instanceof RespawnAnchorBlock
				|| b instanceof CakeBlock
				|| b instanceof CandleBlock
				|| b instanceof CandleCakeBlock
				|| b instanceof AbstractCauldronBlock
				|| b instanceof AbstractRedstoneGateBlock
				|| b instanceof RedstoneWireBlock
				|| b instanceof ComposterBlock
				|| b instanceof DragonEggBlock
				|| b instanceof FlowerPotBlock);
	}

	/**
	 * Floor (only if you're over air), then the 4 sides at every height your body
	 * occupies (normally feet + head), then the block above your head.
	 * Spots that already have a block are left out.
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

	/**
	 * Picks the block to use and makes sure it's in the hotbar. Returns the hotbar slot,
	 * or -1 if there's nothing usable anywhere in the inventory.
	 */
	private static int selectBlock(Session s, BlockPos pos) {
		int index = findBestStack(s.inventory, s.world, pos);
		if (index == -1) {
			return -1;
		}
		if (index < HOTBAR_SIZE) {
			return index;
		}
		// It's in the main inventory: swap it into a hotbar slot, just like hovering
		// over it in the inventory and pressing a number key. Undone at the end.
		int hotbarSlot = chooseWorkSlot(s);
		swap(s, index, hotbarSlot);
		s.swaps.add(new int[] {index, hotbarSlot});
		return hotbarSlot;
	}

	/**
	 * Inventory index (0-8 hotbar, 9-35 main inventory) of the best stack to build with:
	 * END STONE first (held item, then hotbar, then the rest of the inventory), and only if
	 * there's no end stone at all, any other full block in the same order.
	 */
	private static int findBestStack(PlayerInventory inventory, ClientWorld world, BlockPos pos) {
		int selected = inventory.getSelectedSlot();

		if (isEndStone(inventory.getStack(selected))) {
			return selected;
		}
		for (int i = 0; i < INVENTORY_SIZE; i++) {
			if (isEndStone(inventory.getStack(i))) {
				return i;
			}
		}

		if (isGoodWallBlock(inventory.getStack(selected), world, pos)) {
			return selected;
		}
		for (int i = 0; i < INVENTORY_SIZE; i++) {
			if (isGoodWallBlock(inventory.getStack(i), world, pos)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isEndStone(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(Items.END_STONE);
	}

	/** Hotbar slot we swap blocks into: an empty one if you have it, otherwise the one you're holding. */
	private static int chooseWorkSlot(Session s) {
		if (s.workSlot == -1) {
			s.workSlot = s.originalSlot;
			for (int i = 0; i < HOTBAR_SIZE; i++) {
				if (s.inventory.getStack(i).isEmpty()) {
					s.workSlot = i;
					break;
				}
			}
		}
		return s.workSlot;
	}

	/** Same click the game sends when you press a number key over an inventory slot. */
	private static void swap(Session s, int inventoryIndex, int hotbarSlot) {
		// In the player's inventory screen, main inventory slots 9-35 have the same numbers.
		s.client.interactionManager.clickSlot(s.player.playerScreenHandler.syncId,
				inventoryIndex, hotbarSlot, SlotActionType.SWAP, s.player);
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
