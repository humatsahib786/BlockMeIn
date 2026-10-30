package com.blockmein;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
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
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * Block Me In.
 *
 * PRESS the key (default G, rebindable in Options > Controls > Key Binds) to turn it on,
 * press again to turn it off. While it's on it boxes you in (4 walls at feet + head height
 * and a roof) and then GUARDS the box: blocks someone else breaks are put straight back.
 * Blocks YOU break are left open.
 *
 * Everything happens "silently":
 *  - Your camera never moves. For each block it tells the server it's looking at the face
 *    it clicks, places the block, and afterwards tells the server your real view again.
 *  - Your held item never changes. It switches to the block, places, and switches back
 *    in the same tick, so your sword stays in your hand.
 *  - Blocks are placed by clicking the face of a real neighbouring block (one you can see
 *    if possible), never into thin air.
 *  - About BLOCKS_PER_TICK blocks per tick, so a full box takes 2-3 ticks.
 *
 * The only time it moves you is right after you turn it on, and only if you're not in the
 * middle of the block (the walls would hit your body): a short walk to the centre.
 *
 * Spots someone is standing in are skipped (no waiting, no holding your hotbar) and filled
 * as soon as they're free.
 *
 * Blocks: END STONE first (anywhere in your inventory), otherwise any other full block.
 */
public class BlockMeInClient implements ClientModInitializer {
	public static final String MOD_ID = "blockmein";

	// ---------------- Tuning ----------------

	/** Max blocks placed per tick (20 ticks = 1 second). 4 = full box in about 3 ticks. */
	private static final int BLOCKS_PER_TICK = 4;
	/** If the server didn't accept a block, wait this many ticks before trying that spot again. */
	private static final int RETRY_TICKS = 5;
	/** Your body is 0.6 wide: closer than this to the middle and the walls fit around you. */
	private static final double FITS_TOLERANCE = 0.18;
	/** When walking to the middle, stop once you're this close. */
	private static final double CENTER_TOLERANCE = 0.1;
	/** Ignore position errors smaller than this when steering (prevents jitter). */
	private static final double MOVE_DEADZONE = 0.03;
	/** How far ahead it predicts your sliding when deciding to brake. */
	private static final double BRAKE_FACTOR = 1.2;
	/** Give up walking to the middle after this many ticks and just place what fits. */
	private static final int MAX_CENTER_TICKS = 30;
	/** Max distance to the face it clicks (normal survival reach is 4.5). */
	private static final double REACH = 4.5;

	// ----------------------------------------

	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

	/** Where we look for something to click on: below first, then the sides, then above. */
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
	};
	/** Horizontal directions in the order yaw increases (turning right). */
	private static final Direction[] TURN_ORDER = {
			Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST
	};

	private static final int HOTBAR_SIZE = 9;
	private static final int INVENTORY_SIZE = 36; // hotbar (0-8) + main inventory (9-35)

	/** A face we can click to fill a spot. */
	private record Support(BlockPos block, Direction face, Vec3d point) {}

	private static KeyBinding surroundKey;

	// ---- state while it's on ----
	private static boolean active;
	private static BlockPos anchor;              // the block you're boxed into
	private static boolean centering;            // walking you to the middle (only right after turning on)
	private static int centerTicks;
	private static boolean controllingKeys;
	private static int tickCounter;
	/** Blocks YOU broke while it was on. Never re-placed (until something fills them again). */
	private static final Set<BlockPos> ownBroken = new HashSet<>();
	/** Spots we just tried: don't spam them if the server said no. */
	private static final Map<BlockPos, Integer> retryAt = new HashMap<>();
	private static boolean boxedShown;
	private static boolean noBlocksShown;

	@Override
	public void onInitializeClient() {
		surroundKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.blockmein.surround",   // name shown in the Controls menu
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,            // default key; change it in-game
				CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(BlockMeInClient::onTick);

		// Remember blocks YOU break, so they're not put back.
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
			if (active) {
				ownBroken.add(pos.toImmutable());
			}
		});
	}

	// ================================ main loop ================================

	private static void onTick(MinecraftClient client) {
		tickCounter++;
		boolean pressed = false;
		while (surroundKey.wasPressed()) {
			pressed = true;
		}
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null
				|| client.getNetworkHandler() == null) {
			if (active) {
				stop(client, null);
			}
			return;
		}
		if (pressed) {
			if (active) {
				stop(client, "blockmein.message.off");
				return;
			}
			start(player);
			message(client, "blockmein.message.on");
		}
		if (!active) {
			return;
		}
		if (!player.isAlive() || player.isSpectator()) {
			stop(client, "blockmein.message.off");
			return;
		}
		if (client.currentScreen != null) {
			releaseKeys(); // paused while a menu/chat is open
			return;
		}
		tickActive(client, player, client.world);
	}

	private static void start(ClientPlayerEntity player) {
		active = true;
		anchor = player.getBlockPos();
		centering = true;
		centerTicks = 0;
		ownBroken.clear();
		retryAt.clear();
		boxedShown = false;
		noBlocksShown = false;
	}

	private static void stop(MinecraftClient client, String messageKey) {
		releaseKeys();
		active = false;
		ownBroken.clear();
		retryAt.clear();
		if (messageKey != null) {
			message(client, messageKey);
		}
	}

	private static void tickActive(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		ownBroken.removeIf(pos -> !world.getBlockState(pos).isReplaceable()); // filled again -> protect again
		retryAt.values().removeIf(t -> t <= tickCounter);

		// ---- Still in the box? ----
		if (player.isOnGround() && MathHelper.floor(player.getY() + 1.0E-4) != anchor.getY()) {
			if (ownBroken.contains(anchor.down())) {
				stop(client, "blockmein.message.left"); // you dug down
				return;
			}
			// Floor got broken / you got moved up or down: box up again where you are.
			anchor = player.getBlockPos();
			centering = true;
			centerTicks = 0;
			boxedShown = false;
		}
		double offX = player.getX() - (anchor.getX() + 0.5);
		double offZ = player.getZ() - (anchor.getZ() + 0.5);
		if (Math.abs(offX) > 0.5 || Math.abs(offZ) > 0.5) {
			if (centering) {
				anchor = player.getBlockPos(); // got pushed while setting up: use the new spot
				centerTicks = 0;
			} else {
				stop(client, "blockmein.message.left");
				return;
			}
		}

		// ---- Only right after turning on: walk to the middle if the walls wouldn't fit ----
		if (centering) {
			if (!player.isOnGround()) {
				return; // wait until you land
			}
			boolean fits = isFullyBoxed(world, player)
					|| (Math.abs(offX) <= FITS_TOLERANCE && Math.abs(offZ) <= FITS_TOLERANCE);
			boolean close = Math.abs(offX) <= CENTER_TOLERANCE && Math.abs(offZ) <= CENTER_TOLERANCE;
			if ((!fits || (controllingKeys && !close)) && ++centerTicks <= MAX_CENTER_TICKS) {
				steerTo(client, player, anchor.getX() + 0.5, anchor.getZ() + 0.5);
				return;
			}
			centering = false; // from now on it never moves you again
			releaseKeys();
		}

		// ---- Place (silently) ----
		if (player.isUsingItem()) {
			return; // don't interrupt eating, blocking with a shield, drawing a bow...
		}
		placeSilently(client, player, world);

		if (isFullyBoxed(world, player)) {
			if (!boxedShown) {
				message(client, "blockmein.message.boxed");
				boxedShown = true;
			}
		} else {
			boxedShown = false;
		}
	}

	// ================================ placing ================================

	/**
	 * Fill as many open spots as possible this tick (up to BLOCKS_PER_TICK). For each one:
	 * pick the block, tell the server we look at the face we click, click it. Afterwards put
	 * the held slot, inventory and the server's idea of your view back the way they were.
	 */
	private static void placeSilently(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		PlayerInventory inventory = player.getInventory();
		int userSlot = inventory.getSelectedSlot();
		float realYaw = player.getYaw();
		float realPitch = player.getPitch();
		List<int[]> swaps = new ArrayList<>();
		int[] workSlot = {-1};
		int placed = 0;
		boolean outOfBlocks = false;

		Box body = player.getBoundingBox();
		int x = anchor.getX();
		int z = anchor.getZ();
		int minY = MathHelper.floor(body.minY + 1.0E-4);
		int maxY = MathHelper.floor(body.maxY - 1.0E-4);
		BlockPos roof = new BlockPos(x, maxY + 1, z);

		List<BlockPos> targets = new ArrayList<>();
		for (Direction side : sidesInTurnOrder(realYaw)) {
			for (int y = minY; y <= maxY; y++) {
				targets.add(new BlockPos(x, y, z).offset(side));
			}
		}
		targets.add(roof);

		for (BlockPos target : targets) {
			if (placed >= BLOCKS_PER_TICK || outOfBlocks) {
				break;
			}
			if (!isOpen(world, target)) {
				continue;
			}

			Support support = findSupport(world, player, target);
			BlockPos placeAt = target;
			if (support == null && target.equals(roof)) {
				// Nothing next to the roof spot: first put a block on top of one of the
				// head-level walls; the roof then goes against its side.
				for (Direction side : sidesInTurnOrder(realYaw)) {
					BlockPos wall = new BlockPos(x, maxY, z).offset(side);
					BlockPos helper = wall.up();
					BlockState wallState = world.getBlockState(wall);
					if (!wallState.isReplaceable() && isSafeToClick(wallState) && isOpen(world, helper)) {
						Support s = supportOn(world, player, wall, Direction.UP, true);
						if (s != null) {
							support = s;
							placeAt = helper;
							break;
						}
					}
				}
			}
			if (support == null) {
				continue;
			}

			int slot = selectBlock(client, player, world, placeAt, swaps, workSlot, userSlot);
			if (slot == -1) {
				outOfBlocks = true;
				break;
			}
			// Someone (or you) standing in the spot? Skip it for now, no waiting.
			Block block = ((BlockItem) inventory.getStack(slot).getItem()).getBlock();
			if (!world.canPlace(block.getDefaultState(), placeAt, ShapeContext.absent())) {
				continue;
			}

			if (clickSilently(client, player, slot, support)) {
				placed++;
			}
			retryAt.put(placeAt.toImmutable(), tickCounter + RETRY_TICKS);

			// Helper went in: try the roof right away against it.
			if (!placeAt.equals(target) && placed < BLOCKS_PER_TICK && isOpen(world, roof)) {
				Support roofSupport = findSupport(world, player, roof);
				if (roofSupport != null) {
					int roofSlot = selectBlock(client, player, world, roof, swaps, workSlot, userSlot);
					if (roofSlot != -1) {
						Block roofBlock = ((BlockItem) inventory.getStack(roofSlot).getItem()).getBlock();
						if (world.canPlace(roofBlock.getDefaultState(), roof, ShapeContext.absent())
								&& clickSilently(client, player, roofSlot, roofSupport)) {
							placed++;
						}
						retryAt.put(roof.toImmutable(), tickCounter + RETRY_TICKS);
					}
				}
			}
		}

		// ---- Put everything back ----
		for (int i = swaps.size() - 1; i >= 0; i--) {   // undo inventory swaps, newest first
			swap(client, player, swaps.get(i)[0], swaps.get(i)[1]);
		}
		inventory.setSelectedSlot(userSlot);
		if (placed > 0) {
			sendLook(client, player, realYaw, realPitch); // server sees your real view again
		}

		if (outOfBlocks) {
			if (!noBlocksShown) {
				message(client, "blockmein.message.no_blocks");
				noBlocksShown = true;
			}
		} else {
			noBlocksShown = false;
		}
	}

	/** Empty, not opened by you, and not tried a moment ago. */
	private static boolean isOpen(ClientWorld world, BlockPos pos) {
		return world.getBlockState(pos).isReplaceable()
				&& !ownBroken.contains(pos)
				&& !retryAt.containsKey(pos);
	}

	/** Look (server-side only) at the face and right-click it with the block in {@code slot}. */
	private static boolean clickSilently(MinecraftClient client, ClientPlayerEntity player, int slot, Support support) {
		Vec3d eye = player.getEyePos();
		double dx = support.point().x - eye.x;
		double dy = support.point().y - eye.y;
		double dz = support.point().z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90.0F, 90.0F);
		sendLook(client, player, yaw, pitch);

		player.getInventory().setSelectedSlot(slot);
		BlockHitResult hit = new BlockHitResult(support.point(), support.face(), support.block(), false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (result.isAccepted()) {
			// Arm swing for the server only (no animation on your screen).
			client.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
			return true;
		}
		return false;
	}

	private static void sendLook(MinecraftClient client, ClientPlayerEntity player, float yaw, float pitch) {
		client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
				yaw, pitch, player.isOnGround(), player.horizontalCollision));
	}

	/**
	 * A face of an existing neighbour we can click to fill {@code target}. Prefers faces you
	 * could actually see from where you stand; otherwise any face within reach.
	 */
	private static Support findSupport(ClientWorld world, ClientPlayerEntity player, BlockPos target) {
		for (boolean mustSee : new boolean[] {true, false}) {
			for (Direction dir : SUPPORT_ORDER) {
				BlockPos neighbor = target.offset(dir);
				BlockState state = world.getBlockState(neighbor);
				if (state.isReplaceable() || !isSafeToClick(state)) {
					continue;
				}
				Support s = supportOn(world, player, neighbor, dir.getOpposite(), mustSee);
				if (s != null) {
					return s;
				}
			}
		}
		return null;
	}

	/** A clickable point on {@code face} of {@code block}: visible if possible, always within reach. */
	private static Support supportOn(ClientWorld world, ClientPlayerEntity player, BlockPos block,
			Direction face, boolean mustSee) {
		Vec3d eye = player.getEyePos();
		Vec3d faceCenter = Vec3d.ofCenter(block).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
		double[][] offsets = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
		for (double[] o : offsets) {
			Vec3d point = switch (face.getAxis()) {
				case X -> faceCenter.add(0, o[0], o[1]);
				case Y -> faceCenter.add(o[0], 0, o[1]);
				case Z -> faceCenter.add(o[0], o[1], 0);
			};
			if (eye.distanceTo(point) > REACH - 0.2) {
				continue;
			}
			if (!mustSee) {
				return new Support(block, face, point);
			}
			Vec3d inside = point.add(face.getOffsetX() * -0.05, face.getOffsetY() * -0.05, face.getOffsetZ() * -0.05);
			BlockHitResult hit = world.raycast(new RaycastContext(
					eye, inside, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
			if (hit != null && hit.getType() == HitResult.Type.BLOCK
					&& hit.getBlockPos().equals(block) && hit.getSide() == face) {
				return new Support(block, face, point);
			}
		}
		return null;
	}

	private static boolean isFullyBoxed(ClientWorld world, ClientPlayerEntity player) {
		Box body = player.getBoundingBox();
		int minY = MathHelper.floor(body.minY + 1.0E-4);
		int maxY = MathHelper.floor(body.maxY - 1.0E-4);
		for (int y = minY; y <= maxY; y++) {
			for (Direction side : TURN_ORDER) {
				if (world.getBlockState(new BlockPos(anchor.getX(), y, anchor.getZ()).offset(side)).isReplaceable()) {
					return false;
				}
			}
		}
		return !world.getBlockState(new BlockPos(anchor.getX(), maxY + 1, anchor.getZ())).isReplaceable();
	}

	/** The 4 sides, starting with the one you're facing, then turning right. */
	private static Direction[] sidesInTurnOrder(float yaw) {
		int start = Math.floorMod(Math.round(yaw / 90.0F), 4);
		Direction[] out = new Direction[4];
		for (int i = 0; i < 4; i++) {
			out[i] = TURN_ORDER[(start + i) % 4];
		}
		return out;
	}

	// ================================ moving (only right after turning on) ================================

	/**
	 * Walk toward the middle of the block by pressing your movement keys, predicting how
	 * far you'll slide so it lets go (or counter-steers) in time instead of overshooting.
	 */
	private static void steerTo(MinecraftClient client, ClientPlayerEntity player, double x, double z) {
		Vec3d v = player.getVelocity();
		double errX = x - (player.getX() + v.x * BRAKE_FACTOR);
		double errZ = z - (player.getZ() + v.z * BRAKE_FACTOR);
		double yaw = Math.toRadians(player.getYaw());
		double forward = -errX * Math.sin(yaw) + errZ * Math.cos(yaw);
		double left = errX * Math.cos(yaw) + errZ * Math.sin(yaw);
		GameOptions o = client.options;
		o.forwardKey.setPressed(forward > MOVE_DEADZONE);
		o.backKey.setPressed(forward < -MOVE_DEADZONE);
		o.leftKey.setPressed(left > MOVE_DEADZONE);
		o.rightKey.setPressed(left < -MOVE_DEADZONE);
		controllingKeys = true;
	}

	/** Give the movement keys back: re-read which keys you're physically holding. */
	private static void releaseKeys() {
		if (controllingKeys) {
			controllingKeys = false;
			KeyBinding.updatePressedStates();
		}
	}

	// ================================ blocks & inventory ================================

	/**
	 * Picks the block to use and makes sure it's in the hotbar. Returns the hotbar slot,
	 * or -1 if there's nothing usable anywhere in the inventory. Swaps are recorded so the
	 * caller can undo them at the end of the tick.
	 */
	private static int selectBlock(MinecraftClient client, ClientPlayerEntity player, ClientWorld world,
			BlockPos pos, List<int[]> swaps, int[] workSlot, int userSlot) {
		PlayerInventory inventory = player.getInventory();
		int index = findBestStack(inventory, world, pos);
		if (index == -1) {
			return -1;
		}
		if (index < HOTBAR_SIZE) {
			return index;
		}
		// In the main inventory: swap it into a hotbar slot (same click as pressing a number
		// key over it in the inventory). Swapped back at the end of this tick.
		if (workSlot[0] == -1) {
			workSlot[0] = userSlot;
			for (int i = 0; i < HOTBAR_SIZE; i++) {
				if (inventory.getStack(i).isEmpty()) {
					workSlot[0] = i;
					break;
				}
			}
		}
		swap(client, player, index, workSlot[0]);
		swaps.add(new int[] {index, workSlot[0]});
		return workSlot[0];
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

	/** Same click the game sends when you press a number key over an inventory slot. */
	private static void swap(MinecraftClient client, ClientPlayerEntity player, int inventoryIndex, int hotbarSlot) {
		// In the player's inventory screen, main inventory slots 9-35 have the same numbers.
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId,
				inventoryIndex, hotbarSlot, SlotActionType.SWAP, player);
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

	/** Never click blocks that would open, toggle or use something. */
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

	private static void message(MinecraftClient client, String key) {
		client.inGameHud.setOverlayMessage(Text.translatable(key), false);
	}
}
