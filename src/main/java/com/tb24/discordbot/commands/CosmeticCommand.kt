package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.ItemArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.EAthenaCustomizationCategory
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.MarkItemSeen
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.SetCosmeticLockerSlot
import com.tb24.fn.model.mcpprofile.commands.subgame.SetItemFavoriteStatus
import com.tb24.fn.model.mcpprofile.item.FortCosmeticLockerItem
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticItemTexture
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticProfileBannerVariant
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticVariantBackedByArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenuInteraction
import java.util.concurrent.CompletableFuture
import kotlin.jvm.internal.Ref

val equipEmote = getEmoteByName("akl_equip")
val editStylesEmote = getEmoteByName("akl_editStyles")
val favoritedEmote = getEmoteByName("akl_favorited")
val favoriteEmote = getEmoteByName("akl_favorite")
val bangEmote = getEmoteByName("akl_new")

class CosmeticCommand : BrigadierCommand("cosmetic", "Shows info and options about a BR cosmetic you own.", arrayOf("c")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("type", StringArgumentType.string())
			.then(argument("item", ItemArgument.item(true))
				.executes {
					val itemType = parseCosmeticType(StringArgumentType.getString(it, "type"))
					val source = it.source
					source.ensureSession()
					source.loading("Getting cosmetics")
					source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
					val athena = source.api.profileManager.getProfileData("athena")
					execute(it.source, ItemArgument.getItem(it, "item", athena, itemType), athena)
				}
			)
		)
}

class CampaignCosmeticCommand : BrigadierCommand("stwcosmetic", "Shows info and options about a BR cosmetic you own, and interact with STW locker instead of BR.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("type", StringArgumentType.string())
			.then(argument("item", ItemArgument.item(true))
				.executes {
					val itemType = parseCosmeticType(StringArgumentType.getString(it, "type"))
					val source = it.source
					source.ensureSession()
					source.loading("Getting cosmetics")
					CompletableFuture.allOf(
						source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena"),
						source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign")
					).await()
					val athena = source.api.profileManager.getProfileData("athena")
					val campaign = source.api.profileManager.getProfileData("campaign")
					execute(it.source, ItemArgument.getItem(it, "item", athena, itemType), campaign)
				}
			)
		)
}

private fun execute(source: CommandSourceStack, item: FortItemStack, profile: McpProfile, alert: String? = null): Int {
	val defData = item.defData as? AthenaCosmeticItemDefinition ?: throw SimpleCommandExceptionType(LiteralMessage("Not found")).create()
	val embed = EmbedBuilder().setColor(item.palette.Color2.toColor())
		.setAuthor((item.defData?.Series?.value?.DisplayName ?: item.rarity.rarityName).format() + " \u00b7 " + item.shortDescription.format())
		.setTitle((if (item.isItemSeen) "" else bangEmote?.asMention + ' ') + item.displayName.ifEmpty { defData.name })
		.setDescription(item.description)
		.setThumbnail(Utils.benBotExportAsset(item.getPreviewImagePath(true)?.toString()))
	val buttons = mutableListOf<Button>()

	// Prepare loadout stuff
	val loadoutItemId = Ref.ObjectRef<String>()
	val currentLoadout = FortCosmeticLockerItem.getFromProfile(profile, loadoutItemId)
		?: throw SimpleCommandExceptionType(LiteralMessage("Main preset not found. Must be a bug.")).create()
	val category = when (defData) {
		is AthenaCharacterItemDefinition -> EAthenaCustomizationCategory.Character
		is AthenaBackpackItemDefinition -> EAthenaCustomizationCategory.Backpack
		is AthenaPickaxeItemDefinition -> EAthenaCustomizationCategory.Pickaxe
		is AthenaGliderItemDefinition -> EAthenaCustomizationCategory.Glider
		is AthenaSkyDiveContrailItemDefinition -> EAthenaCustomizationCategory.SkyDiveContrail
		is AthenaDanceItemDefinition -> EAthenaCustomizationCategory.Dance
		is AthenaItemWrapDefinition -> EAthenaCustomizationCategory.ItemWrap
		is AthenaMusicPackItemDefinition -> EAthenaCustomizationCategory.MusicPack
		is AthenaLoadingScreenItemDefinition -> EAthenaCustomizationCategory.LoadingScreen
		else -> throw SimpleCommandExceptionType(LiteralMessage("Unsupported cosmetic type " + defData.exportType)).create()
	}

	// Check if equipped
	val numItems = category.numItems
	if (numItems == 1) {
		val isEquipped = currentLoadout.locker_slots_data.getSlotItems(category).firstOrNull() == item.templateId
		if (isEquipped) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "equip", "Equipped", Emoji.fromUnicode("✅")).asDisabled())
		} else {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "equip", "Equip", Emoji.fromEmote(equipEmote!!)))
		}
	} else {
		val equippedIndices = currentLoadout.locker_slots_data.getSlotItems(category).withIndex().filter { it.value == item.templateId }.map { it.index + 1 }
		if (equippedIndices.isNotEmpty()) {
			embed.setFooter("Equipped at slot(s) " + equippedIndices.joinToString(", "))
		}
		buttons.add(Button.of(ButtonStyle.SECONDARY, "equipTo", "Equip to...", Emoji.fromEmote(equipEmote!!)))
	}

	// Prepare variants
	val variants = mutableListOf<VariantContainer>()
	defData.ItemVariants?.forEach { lazyVariant ->
		val cosmeticVariant = lazyVariant.value
		if (cosmeticVariant is FortCosmeticProfileBannerVariant) {
			return@forEach
		}
		variants.add(VariantContainer(cosmeticVariant, EpicApi.GSON.fromJson(item.attributes.getAsJsonArray("variants"), Array<McpVariantReader>::class.java)))
	}
	if (variants.isNotEmpty()) {
		embed.addField("Styles", variants.joinToString("\n") {
			"%s (%s)".format(it.channelName, it.activeVariantDisplayName)
		}, false)
		if (numItems == 1) { // TODO support editing variants for multiple slot items
			buttons.add(Button.of(ButtonStyle.SECONDARY, "editVariants", "Edit styles", Emoji.fromEmote(editStylesEmote!!)))
		}
	}

	// Favorite button
	if (item.isFavorite) {
		buttons.add(Button.of(ButtonStyle.SUCCESS, "favorite", "Favorited", Emoji.fromEmote(favoritedEmote!!)))
	} else {
		buttons.add(Button.of(ButtonStyle.SECONDARY, "favorite", "Favorite", Emoji.fromEmote(favoriteEmote!!)))
	}

	// Mark seen button
	if (!item.isItemSeen) {
		buttons.add(Button.of(ButtonStyle.SECONDARY, "markSeen", "Mark seen", Emoji.fromEmote(bangEmote!!)))
	}

	val message = source.complete(alert, embed.build(), ActionRow.of(buttons))
	source.loadingMsg = message
	return when (message.awaitOneInteraction(source.author, false).componentId) {
		"equip" -> equip(source, profile.profileId, loadoutItemId.element, category, item, 0)
		"equipTo" -> equipTo(source, profile.profileId, currentLoadout, loadoutItemId.element, category, item)
		"editVariants" -> editVariants(source, profile.profileId, loadoutItemId.element, category, item, variants)
		"favorite" -> toggleFavorite(source, item)
		"markSeen" -> markSeen(source, item)
		else -> Command.SINGLE_SUCCESS
	}
}

private fun equipTo(source: CommandSourceStack, profileId: String, currentLoadout: FortCosmeticLockerItem, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variantUpdates: Array<McpVariantReader>? = emptyArray()): Int {
	val numSlots = category.numItems
	// Slot <N> (<current item>)
	val buttons = mutableListOf<Button>()
	for (slotIndex in 0 until numSlots) {
		val currentItem = currentLoadout.locker_slots_data.slots[category]?.items?.getOrNull(slotIndex)
		val currentItemName = if (!currentItem.isNullOrEmpty()) FortItemStack(currentItem, 1).displayName.format() else "None"
		buttons.add(Button.of(ButtonStyle.PRIMARY, slotIndex.toString(), "Slot ${slotIndex + 1} ($currentItemName)"))
	}
	buttons.add(Button.of(ButtonStyle.PRIMARY, "-1", "All slots"))
	buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌")))
	val message = source.complete("**To which slot?**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
	source.loadingMsg = message
	val choice = message.awaitOneInteraction(source.author, false).componentId
	if (choice == "cancel") {
		return execute(source, item, source.api.profileManager.getProfileData("athena"))
	}
	val slotIndex = choice.toIntOrNull() ?: return Command.SINGLE_SUCCESS
	return equip(source, profileId, lockerItem, category, item, slotIndex, variantUpdates)
}

private fun editVariants(source: CommandSourceStack, profileId: String, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variants: List<VariantContainer>): Int {
	if (variants.size == 1) {
		return editVariant(source, profileId, lockerItem, category, item, variants.first())
	}
	val buttons = mutableListOf<Button>()
	for (variant in variants) {
		buttons.add(Button.of(ButtonStyle.PRIMARY, variant.cosmeticVariant.backendChannelName, "%s (%s)".format(variant.channelName, variant.activeVariantDisplayName)))
	}
	buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌")))
	val message = source.complete("**Select style to edit**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
	source.loadingMsg = message
	val choice = message.awaitOneInteraction(source.author, false).componentId
	if (choice == "cancel") {
		return execute(source, item, source.api.profileManager.getProfileData("athena"))
	}
	val variant = variants.firstOrNull { it.cosmeticVariant.backendChannelName == choice } ?: return Command.SINGLE_SUCCESS
	return editVariant(source, profileId, lockerItem, category, item, variant)
}

private fun editVariant(source: CommandSourceStack, profileId: String, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variant: VariantContainer): Int {
	val (cosmeticVariant, backendVariant) = variant
	val embed = EmbedBuilder().setColor(item.palette.Color2.toColor())
		.setTitle("✏ Editing: " + variant.channelName)
		.setDescription("**Current:** " + variant.activeVariantDisplayName + '\n')
	val selected = if (cosmeticVariant is FortCosmeticItemTexture) {
		embed.appendDescription("Emoticon or spray?")
		val message = source.complete(null, embed.build(), ActionRow.of(
			Button.of(ButtonStyle.PRIMARY, "AthenaEmojiItemDefinition", "Emoticon"),
			Button.of(ButtonStyle.PRIMARY, "AthenaSprayItemDefinition", "Spray"),
			Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌"))
		))
		source.loadingMsg = message
		val interaction = message.awaitOneInteraction(source.author, false)
		val choice = interaction.componentId
		if (choice == "cancel") {
			return execute(source, item, source.api.profileManager.getProfileData("athena"))
		}
		if (choice == "AthenaSprayItemDefinition") {
			source.ensurePremium("Use sprays in emoticon slots")
		}
		val typeName = (interaction.component as Button).label
		source.loadingMsg = source.complete("Type in the exact name of the %s that you want to pick, or `cancel` to cancel:".format(typeName))
		val search = source.channel.awaitMessages({ collected, _, _ -> collected.author == source.author }, AwaitMessagesOptions().apply {
			max = 1
			time = 60000L
			errors = arrayOf(CollectorEndReason.TIME)
		}).await().first().contentRaw
		if (search == "cancel") {
			return execute(source, item, source.api.profileManager.getProfileData("athena"))
		}
		source.loading("Searching for `$search`")
		var result: String? = null
		for ((templateId, objectPath) in AssetManager.INSTANCE.assetRegistry.templateIdToObjectPathMap.entries) {
			if (!templateId.startsWith("athenadance")) {
				continue
			}
			val itemDef = loadObject<FortItemDefinition>(objectPath) ?: continue
			if (itemDef.exportType != choice) {
				continue
			}
			if (itemDef.DisplayName.format()?.trim()?.equals(search, true) == true) {
				result = templateId.substringAfter(':')
				break
			}
		}
		if (result == null) {
			return execute(source, item, source.api.profileManager.getProfileData("athena"), "⚠ No %s found for `%s`".format(typeName, search))
		}
		"ItemTexture.AthenaDance:$result"
	} else if (cosmeticVariant is FortCosmeticVariantBackedByArray) {
		embed.appendDescription("Pick a new style to apply.")
		val selectionMenu = SelectionMenu.create("choice")
		val variantDefs = cosmeticVariant.variants ?: emptyList()
		for (variantDef in variantDefs) {
			val backendName = variantDef.backendVariantName
			var lockReason: String? = null
			if (!variantDef.bStartUnlocked) {
				val owned = backendVariant != null && backendVariant.owned.contains(backendName) // TODO might be better scanning variant token items
				if (!owned) {
					if (variantDef.bHideIfNotOwned) {
						continue
					}
					lockReason = variantDef.UnlockRequirements.format() ?: "Unknown"
				}
			}
			if (lockReason == null) {
				selectionMenu.addOption(variantDef.VariantName.format() ?: variantDef.backendVariantName, variantDef.backendVariantName)
			} else {
				selectionMenu.addOption((variantDef.VariantName.format() ?: variantDef.backendVariantName) + " (" + lockReason.format() + ")", variantDef.backendVariantName, Emoji.fromEmote(lockEmote!!))
			}
		}
		val message = source.complete(null, embed.build(), ActionRow.of(selectionMenu.build()), ActionRow.of(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌"))))
		source.loadingMsg = message
		val interaction = message.awaitOneInteraction(source.author, false)
		if (interaction.componentId == "cancel") {
			return execute(source, item, source.api.profileManager.getProfileData("athena"))
		}
		val selectedVariant = (interaction as SelectionMenuInteraction).selectedOptions!!.first()
		if (selectedVariant.emoji != null) {
			throw SimpleCommandExceptionType(LiteralMessage("That style is locked.")).create()
		}
		val selectedVariantName = selectedVariant.value
		variantDefs.firstOrNull { it.backendVariantName == selectedVariantName }?.backendVariantName ?: return Command.SINGLE_SUCCESS
	} else {
		throw SimpleCommandExceptionType(LiteralMessage("Unsupported ${cosmeticVariant.exportType} ${variant.channelName}. Channel tag is ${variant.cosmeticVariant.backendChannelName}. Please report this to the devs.")).create()
	}
	return equip(source, profileId, lockerItem, category, item, 0, arrayOf(McpVariantReader(cosmeticVariant.backendChannelName, selected, emptyArray())))
}

private fun equip(source: CommandSourceStack, profileId: String, inLockerItem: String, inCategory: EAthenaCustomizationCategory, item: FortItemStack, inSlotIndex: Int, inVariantUpdates: Array<McpVariantReader>? = emptyArray()): Int {
	source.api.profileManager.dispatchClientCommandRequest(SetCosmeticLockerSlot().apply {
		lockerItem = inLockerItem
		category = inCategory
		itemToSlot = item.templateId
		slotIndex = inSlotIndex
		variantUpdates = inVariantUpdates
		optLockerUseCountOverride = -1
	}, profileId).await()
	if (profileId == "athena") {
		source.session.avatarCache.remove(source.api.currentLoggedIn.id)
	}
	return execute(source, item, source.api.profileManager.getProfileData(profileId))
}

private fun toggleFavorite(source: CommandSourceStack, item: FortItemStack): Int {
	source.api.profileManager.dispatchClientCommandRequest(SetItemFavoriteStatus().apply {
		targetItemId = item.itemId
		bFavorite = !item.isFavorite
	}, "athena").await()
	return execute(source, item, source.api.profileManager.getProfileData("athena"))
}

private fun markSeen(source: CommandSourceStack, item: FortItemStack): Int {
	source.api.profileManager.dispatchClientCommandRequest(MarkItemSeen().apply {
		itemIds = arrayOf(item.itemId)
	}, "athena").await()
	return execute(source, item, source.api.profileManager.getProfileData("athena"))
}

private val EAthenaCustomizationCategory.numItems get() = when (this) {
	EAthenaCustomizationCategory.Dance -> 6
	EAthenaCustomizationCategory.ItemWrap -> 7
	else -> 1
}