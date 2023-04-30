package me.gabber235.typewriter.ui

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOClient
import com.github.shynixn.mccoroutine.launchAsync
import com.google.gson.*
import lirand.api.extensions.events.listen
import me.gabber235.typewriter.Typewriter.Companion.plugin
import me.gabber235.typewriter.entry.*
import me.gabber235.typewriter.events.TypewriterReloadEvent
import me.gabber235.typewriter.ui.StagingState.*
import me.gabber235.typewriter.utils.*
import java.io.File
import kotlin.time.Duration.Companion.seconds

object ClientSynchronizer {
	private val pages: MutableMap<String, JsonObject> = mutableMapOf()
	private lateinit var adapters: JsonElement

	private lateinit var gson: Gson

	private val autoSaver =
		Timeout(
			3.seconds,
			ClientSynchronizer::saveStaging,
			immediateRunnable = {
				// When called to save, we immediately want to update the staging state
				if (stagingState != PUBLISHING) stagingState = STAGING
			})

	private val stagingDir
		get() = plugin.dataFolder["staging"]

	private val publishedDir
		get() = plugin.dataFolder["pages"]

	var stagingState = PUBLISHED
		private set(value) {
			field = value
			CommunicationHandler.server?.broadcastOperations?.sendEvent("stagingState", value.name.lowercase())
		}

	fun initialize() {
		gson = EntryDatabase.gson()

		loadState()

		plugin.listen<TypewriterReloadEvent> { loadState() }

		// Read the adapters from the file
		adapters = gson.fromJson(plugin.dataFolder["adapters.json"].readText(), JsonElement::class.java)
	}

	private fun loadState() {
		stagingState = if (stagingDir.exists()) {
			// Migrate staging directory to use the new format
			stagingDir.migrateIfNecessary()

			val stagingPages = fetchPages(stagingDir)
			val publishedPages = fetchPages(publishedDir)

			if (stagingPages == publishedPages) PUBLISHED else STAGING
		} else PUBLISHED

		// Read the pages from the file
		val dir =
			if (stagingState == STAGING) stagingDir else publishedDir
		pages.putAll(fetchPages(dir))
	}

	private fun fetchPages(dir: File): Map<String, JsonObject> {
		val pages = mutableMapOf<String, JsonObject>()
		dir.pages().forEach { file ->
			val page = file.readText()
			pages[file.nameWithoutExtension] = gson.fromJson(page, JsonObject::class.java)
		}
		return pages
	}

	fun handleFetchRequest(client: SocketIOClient, data: String, ack: AckRequest) {
		if (data == "pages") {
			val array = JsonArray()
			pages.forEach { (_, page) ->
				array.add(page)
			}
			ack.sendAckData(array.toString())
		} else if (data == "adapters") {
			ack.sendAckData(adapters.toString())
		}

		ack.sendAckData("No data found")
	}

	fun handleCreatePage(client: SocketIOClient, data: String, ack: AckRequest) {
		val json = gson.fromJson(data, PageCreate::class.java)
		if (pages.containsKey(json.name)) {
			ack.sendAckData("Page already exists")
			return
		}


		val page = gson.fromJson(data, JsonObject::class.java)
		pages[json.name] = page

		ack.sendAckData("Page created")
		CommunicationHandler.server?.broadcastOperations?.sendEvent("createPage", client, data)
		autoSaver()
	}

	fun handleRenamePage(client: SocketIOClient, data: String, ackRequest: AckRequest) {
		val json = gson.fromJson(data, PageRename::class.java)
		val page = pages.remove(json.old) ?: return
		page.addProperty("name", json.new)
		pages[json.new] = page

		ackRequest.sendAckData("Page renamed")
		CommunicationHandler.server?.broadcastOperations?.sendEvent("renamePage", client, data)
		autoSaver()
	}

	fun handleDeletePage(client: SocketIOClient, name: String, ack: AckRequest) {
		if (!pages.containsKey(name)) {
			ack.sendAckData("Page does not exist")
			return
		}

		pages.remove(name)
		// Delete the file
		val file = stagingDir["$name.json"]
		if (file.exists()) {
			file.delete()
		}
		ack.sendAckData("Page deleted")
		CommunicationHandler.server?.broadcastOperations?.sendEvent("deletePage", client, name)
		autoSaver()
	}

	fun handleCreateEntry(client: SocketIOClient, data: String, ack: AckRequest) {
		val json = gson.fromJson(data, EntryCreate::class.java)
		val page = pages[json.pageId] ?: return ack.sendAckData("Page does not exist")
		val entries = page["entries"].asJsonArray
		entries.add(json.entry)
		ack.sendAckData("Entry created")
		CommunicationHandler.server?.broadcastOperations?.sendEvent("createEntry", client, data)
		autoSaver()
	}


	fun handleEntryUpdate(client: SocketIOClient, data: String, ack: AckRequest) {
		val update = gson.fromJson(data, EntryUpdate::class.java)

		// Update the page
		val page =
			pages[update.pageId].logErrorIfNull("A client tried to update a page which does not exists") ?: return
		val entries = page["entries"].asJsonArray
		val entry = entries.find { it.asJsonObject["id"].asString == update.entryId }
			.logErrorIfNull("A client tried to update an entry which does not exists") ?: return

		// Update the entry
		val path = update.path.split(".")
		var current: JsonElement = entry.asJsonObject
		path.forEachIndexed { index, key ->
			if (index == path.size - 1) {
				if (current.isJsonObject) {
					current.asJsonObject.add(key, update.value)
				} else if (current.isJsonArray) {
					current.asJsonArray[Integer.parseInt(key)] = update.value
				}
			} else if (current.isJsonObject) {
				current = current.asJsonObject[key] ?: JsonObject().also { current.asJsonObject.add(key, it) }
			} else if (current.isJsonArray) {
				current =
					current.asJsonArray[Integer.parseInt(key)] ?: JsonObject().also { current.asJsonArray.add(it) }
			}
		}

		CommunicationHandler.server?.broadcastOperations?.sendEvent("updateEntry", client, data)
		autoSaver()
	}

	fun handleCompleteEntryUpdate(client: SocketIOClient, data: String, ack: AckRequest) {
		val update = gson.fromJson(data, CompleteEntryUpdate::class.java)
		val entryId = update.entry["id"].asString

		// Update the page
		val page =
			pages[update.pageId].logErrorIfNull("A client tried to update a page which does not exists") ?: return

		val entries = page["entries"].asJsonArray
		entries.removeAll { entry -> entry.asJsonObject["id"].asString == entryId }
		entries.add(update.entry)

		CommunicationHandler.server?.broadcastOperations?.sendEvent("updateCompleteEntry", client, data)
		autoSaver()
	}

	fun handleReorderEntry(client: SocketIOClient, data: String, ack: AckRequest) {
		val update = gson.fromJson(data, ReorderEntry::class.java)
		val page = pages[update.pageId] ?: return ack.sendAckData("Page does not exist")
		val entries = page["entries"].asJsonArray
		val oldIndex = entries.indexOfFirst { it.asJsonObject["id"].asString == update.entryId }

		if (oldIndex == -1) {
			ack.sendAckData("Entry does not exist")
			return
		}

		var newIndex = update.newIndex

		if (oldIndex == newIndex) {
			ack.sendAckData("Entry is already at the correct index")
			return
		}

		if (oldIndex < newIndex) {
			newIndex--
		}

		val entryAtNewIndex = entries[newIndex]
		entries[newIndex] = entries[oldIndex]
		entries[oldIndex] = entryAtNewIndex

		CommunicationHandler.server?.broadcastOperations?.sendEvent("reorderEntry", client, data)
		autoSaver()
	}


	fun handleDeleteEntry(client: SocketIOClient, data: String, ack: AckRequest) {
		val json = gson.fromJson(data, EntryDelete::class.java)
		val page = pages[json.pageId] ?: return ack.sendAckData("Page does not exist")
		val entries = page["entries"].asJsonArray
		entries.removeAll { entry -> entry.asJsonObject["id"].asString == json.entryId }
		ack.sendAckData("Entry deleted")
		CommunicationHandler.server?.broadcastOperations?.sendEvent("deleteEntry", client, data)
		autoSaver()
	}

	private fun saveStaging() {
		// If we are already publishing, we don't want to save the staging
		if (stagingState == PUBLISHING) return
		val dir = stagingDir

		pages.forEach { (name, page) ->
			val file = dir["$name.json"]
			if (!file.exists()) {
				file.parentFile.mkdirs()
				file.createNewFile()
			}
			file.writeText(page.toString())
		}

		stagingState = STAGING
	}

	fun handlePublish(socketIOClient: SocketIOClient, data: String, ackRequest: AckRequest) {
		if (stagingState != STAGING) {
			ackRequest.sendAckData("No staging state found")
			return
		}

		plugin.launchAsync {
			publish()
		}
		ackRequest.sendAckData("Published")
	}

	fun handleUpdateWriter(client: SocketIOClient, data: String, ack: AckRequest) {
		Writer.updateWriter(client.sessionId.toString(), data)
		CommunicationHandler.server.broadcastWriters()
	}

	// Save the page to the file
	private fun publish() {
		if (stagingState != STAGING) return
		autoSaver.cancel()
		stagingState = PUBLISHING

		stagingState = try {
			pages.forEach { (name, page) ->
				val file = publishedDir["$name.json"]
				file.writeText(page.toString())
			}

			if (stagingDir.exists()) {
				// Check if there are any pages which are no longer in staging. If so, delete them
				val stagingFiles = stagingDir.listFiles()?.map { it.name } ?: emptyList()
				val pagesFiles = publishedDir.listFiles()?.toList() ?: emptyList()

				val deletedPages = pagesFiles.filter { it.name !in stagingFiles }
				deletedPages.backup()
				deletedPages.forEach { it.delete() }
			}

			// Delete the staging folder
			stagingDir.deleteRecursively()
			EntryDatabase.loadEntries()
			plugin.logger.info("Published the staging state")
			PUBLISHED
		} catch (e: Exception) {
			e.printStackTrace()
			STAGING
		}
	}

	fun dispose() {
		if (stagingState == STAGING) saveStaging()
	}
}

enum class StagingState {
	PUBLISHING,
	STAGING,
	PUBLISHED
}

data class PageCreate(val name: String)

data class PageRename(val old: String, val new: String)

data class EntryCreate(
	val pageId: String,
	val entry: JsonObject,
)

data class EntryUpdate(
	val pageId: String,
	val entryId: String,
	val path: String,
	val value: JsonElement
)

data class CompleteEntryUpdate(
	val pageId: String,
	val entry: JsonObject,
)

data class ReorderEntry(
	val pageId: String,
	val entryId: String,
	val newIndex: Int,
)

data class EntryDelete(
	val pageId: String,
	val entryId: String,
)
