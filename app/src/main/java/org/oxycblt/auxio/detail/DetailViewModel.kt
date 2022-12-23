/*
 * Copyright (c) 2021 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.detail

import android.app.Application
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.Header
import org.oxycblt.auxio.list.Item
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.Sort
import org.oxycblt.auxio.music.storage.MimeType
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.util.*

/**
 * [AndroidViewModel] that manages the Song, Album, Artist, and Genre detail views.
 * Keeps track of the current item they are showing, sub-data to display, and configuration.
 * Since this ViewModel requires a context, it must be instantiated [AndroidViewModel]'s Factory.
 * @param application [Application] context required to initialize certain information.
 * @author Alexander Capehart (OxygenCobalt)
 */
class DetailViewModel(application: Application) :
    AndroidViewModel(application), MusicStore.Callback {
    private val musicStore = MusicStore.getInstance()
    private val settings = Settings(application)

    private var currentSongJob: Job? = null

    // --- SONG ---

    private val _currentSong = MutableStateFlow<DetailSong?>(null)
    /**
     * The current [Song] that should be displayed in the [Song] detail view. Null if there
     * is no [Song].
     * TODO: De-couple Song and Properties?
     */
    val currentSong: StateFlow<DetailSong?>
        get() = _currentSong

    // --- ALBUM ---

    private val _currentAlbum = MutableStateFlow<Album?>(null)
    /**
     * The current [Album] that should be displayed in the [Album] detail view. Null if there
     * is no [Album].
     */
    val currentAlbum: StateFlow<Album?>
        get() = _currentAlbum

    private val _albumData = MutableStateFlow(listOf<Item>())
    /**
     * The current list data derived from [currentAlbum], for use in the [Album] detail view.
     */
    val albumList: StateFlow<List<Item>>
        get() = _albumData

    /**
     * The current [Sort] used for [Song]s in the [Album] detail view.
     */
    var albumSort: Sort
        get() = settings.detailAlbumSort
        set(value) {
            settings.detailAlbumSort = value
            // Refresh the album list to reflect the new sort.
            currentAlbum.value?.let(::refreshAlbumList)
        }

    // --- ARTIST ---

    private val _currentArtist = MutableStateFlow<Artist?>(null)
    /**
     * The current [Artist] that should be displayed in the [Artist] detail view. Null if there
     * is no [Artist].
     */
    val currentArtist: StateFlow<Artist?>
        get() = _currentArtist

    private val _artistData = MutableStateFlow(listOf<Item>())
    /**
     * The current list derived from [currentArtist], for use in the [Artist] detail view.
     */
    val artistList: StateFlow<List<Item>> = _artistData

    /**
     * The current [Sort] used for [Song]s in the [Artist] detail view.
     */
    var artistSort: Sort
        get() = settings.detailArtistSort
        set(value) {
            settings.detailArtistSort = value
            // Refresh the artist list to reflect the new sort.
            currentArtist.value?.let(::refreshArtistList)
        }

    // --- GENRE ---

    private val _currentGenre = MutableStateFlow<Genre?>(null)
    /**
     * The current [Genre] that should be displayed in the [Genre] detail view. Null if there
     * is no [Genre].
     */
    val currentGenre: StateFlow<Genre?>
        get() = _currentGenre

    private val _genreData = MutableStateFlow(listOf<Item>())
    /**
     * The current list data derived from [currentGenre], for use in the [Genre] detail view.
     */
    val genreList: StateFlow<List<Item>> = _genreData

    /**
     * The current [Sort] used for [Song]s in the [Genre] detail view.
     */
    var genreSort: Sort
        get() = settings.detailGenreSort
        set(value) {
            settings.detailGenreSort = value
            // Refresh the genre list to reflect the new sort.
            currentGenre.value?.let(::refreshGenreList)
        }

    init {
        musicStore.addCallback(this)
    }

    override fun onCleared() {
        musicStore.removeCallback(this)
    }

    override fun onLibraryChanged(library: MusicStore.Library?) {
        if (library == null) {
            // Nothing to do.
            return
        }

        // If we are showing any item right now, we will need to refresh it (and any information
        // related to it) with the new library in order to prevent stale items from showing up
        // in the UI.

        val song = currentSong.value
        if (song != null) {
            val newSong = library.sanitize(song.song)
            if (newSong != null) {
                loadDetailSong(newSong)
            } else {
                _currentSong.value = null
            }
            logD("Updated song to ${newSong}")
        }

        val album = currentAlbum.value
        if (album != null) {
            _currentAlbum.value = library.sanitize(album)?.also(::refreshAlbumList)
            logD("Updated genre to ${currentAlbum.value}")
        }

        val artist = currentArtist.value
        if (artist != null) {
            _currentArtist.value = library.sanitize(artist)?.also(::refreshArtistList)
            logD("Updated genre to ${currentArtist.value}")
        }

        val genre = currentGenre.value
        if (genre != null) {
            _currentGenre.value = library.sanitize(genre)?.also(::refreshGenreList)
            logD("Updated genre to ${currentGenre.value}")
        }
    }

    /**
     * Set a new [currentSong] from it's [Music.UID]. If the [Music.UID] differs, a new loading
     * process will begin and the newly-loaded [DetailSong] will be set to [currentSong].
     * @param uid The UID of the [Song] to load. Must be valid.
     */
    fun setSongUid(uid: Music.UID) {
        if (_currentSong.value?.run { song.uid } == uid) {
            // Nothing to do.
            return
        }

        loadDetailSong(requireMusic(uid))
    }

    /**
     * Set a new [currentAlbum] from it's [Music.UID]. If the [Music.UID] differs, [currentAlbum]
     * and [albumList] will be updated to align with the new [Album].
     * @param uid The [Music.UID] of the [Album] to update [currentAlbum] to. Must be valid.
     */
    fun setAlbumUid(uid: Music.UID) {
        if (_currentAlbum.value?.uid == uid) {
            // Nothing to do.
            return
        }

        _currentAlbum.value = requireMusic<Album>(uid).also { refreshAlbumList(it) }
    }

    /**
     * Set a new [currentArtist] from it's [Music.UID]. If the [Music.UID] differs, [currentArtist]
     * and [artistList] will be updated to align with the new [Artist].
     * @param uid The [Music.UID] of the [Artist] to update [currentArtist] to. Must be valid.
     */
    fun setArtistUid(uid: Music.UID) {
        if (_currentArtist.value?.uid == uid) {
            // Nothing to do.
            return
        }

        _currentArtist.value = requireMusic<Artist>(uid).also { refreshArtistList(it) }
    }

    /**
     * Set a new [currentGenre] from it's [Music.UID]. If the [Music.UID] differs, [currentGenre]
     * and [genreList] will be updated to align with the new album.
     * @param uid The [Music.UID] of the [Genre] to update [currentGenre] to. Must be valid.
     */
    fun setGenreUid(uid: Music.UID) {
        if (_currentGenre.value?.uid == uid)  {
            // Nothing to do.
            return
        }

        _currentGenre.value = requireMusic<Genre>(uid).also { refreshGenreList(it) }
    }

    /**
     * A wrapper around [MusicStore.Library.find] that asserts that the returned data should
     * be valid.
     * @param T The type of music that should be found
     * @param uid The [Music.UID] of the [T] to find
     * @return A [T] with the given [Music.UID]
     * @throws IllegalStateException If nothing can be found
     */
    private fun <T: Music> requireMusic(uid: Music.UID): T =
        requireNotNull(unlikelyToBeNull(musicStore.library).find(uid)) { "Invalid id provided" }

    /**
     * Start a new job to load a [DetailSong] based on the properties of the given [Song]'s file.
     * @param song The song to load.
     */
    private fun loadDetailSong(song: Song) {
        // Clear any previous job in order to avoid stale data from appearing in the UI.
        currentSongJob?.cancel()
        _currentSong.value = DetailSong(song, null)
        currentSongJob =
            viewModelScope.launch(Dispatchers.IO) {
                val info = loadSongProperties(song)
                yield()
                _currentSong.value = DetailSong(song, info)
            }
    }

    /**
     * Load a new set of [DetailSong.Properties] based on the given [Song]'s file using
     * [MediaExtractor].
     * @param song The song to load the properties from.
     * @return A [DetailSong.Properties] containing the properties that could be
     * extracted from the file.
     */
    private fun loadSongProperties(song: Song): DetailSong.Properties {
        // While we would use ExoPlayer to extract this information, it doesn't support
        // common data like bit rate in progressive data sources due to there being no
        // demand. Thus, we are stuck with the inferior OS-provided MediaExtractor.
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, song.uri, emptyMap())
        } catch (e: Exception) {
            // Can feasibly fail with invalid file formats. Note that this isn't considered
            // an error condition in the UI, as there is still plenty of other song information
            // that we can show.
            logW("Unable to extract song attributes.")
            logW(e.stackTraceToString())
            return DetailSong.Properties(null, null, song.mimeType)
        }

        // Get the first track from the extractor (This is basically always the only
        // track we need to analyze).
        val format = extractor.getTrackFormat(0)

        // Accessing fields can throw an exception if the fields are not present, and
        // the new method for using default values is not available on lower API levels.
        // So, we are forced to handle the exception and map it to a saner null value.
        val bitrate =
            try {
                // Convert bytes-per-second to kilobytes-per-second.
                format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
            } catch (e: NullPointerException) {
                logD("Unable to extract bit rate field")
                null
            }

        val sampleRate =
            try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (e: NullPointerException) {
                logE("Unable to extract sample rate field")
                null
            }

        val resolvedMimeType =
            if (song.mimeType.fromFormat != null) {
                // ExoPlayer was already able to populate the format.
                song.mimeType
            } else {
                // ExoPlayer couldn't populate the format somehow, populate it here.
                val formatMimeType =
                    try {
                        format.getString(MediaFormat.KEY_MIME)
                    } catch (e: NullPointerException) {
                        logE("Unable to extract mime type field")
                        null
                    }

                MimeType(song.mimeType.fromExtension, formatMimeType)
            }

        return DetailSong.Properties(bitrate, sampleRate, resolvedMimeType)
    }

    /**
     * Refresh [albumList] to reflect the given [Album] and any [Sort] changes.
     * @param album The [Album] to create the list from.
     */
    private fun refreshAlbumList(album: Album) {
        logD("Refreshing album data")
        val data = mutableListOf<Item>(album)
        data.add(SortHeader(R.string.lbl_songs))

        // To create a good user experience regarding disc numbers, we group the album's
        // songs up by disc and then delimit the groups by a disc header.
        val songs = albumSort.songs(album.songs)
        // Songs without disc tags become part of Disc 1.
        val byDisc = songs.groupBy { it.disc ?: 1 }
        if (byDisc.size > 1) {
            logD("Album has more than one disc, interspersing headers")
            for (entry in byDisc.entries) {
                data.add(DiscHeader(entry.key))
                data.addAll(entry.value)
            }
        } else {
            // Album only has one disc, don't add any redundant headers
            data.addAll(songs)
        }

        _albumData.value = data
    }

    /**
     * Refresh [artistList] to reflect the given [Artist] and any [Sort] changes.
     * @param artist The [Artist] to create the list from.
     */
    private fun refreshArtistList(artist: Artist) {
        logD("Refreshing artist data")
        val data = mutableListOf<Item>(artist)
        val albums = Sort(Sort.Mode.ByDate, false).albums(artist.albums)

        val byReleaseGroup =
            albums.groupBy {
                // Remap the complicated Album.Type data structure into an easier
                // "AlbumGrouping" enum that will automatically group and sort
                // the artist's albums.
                when (it.type.refinement) {
                    Album.Type.Refinement.LIVE -> AlbumGrouping.LIVE
                    Album.Type.Refinement.REMIX -> AlbumGrouping.REMIXES
                    null ->
                        when (it.type) {
                            is Album.Type.Album -> AlbumGrouping.ALBUMS
                            is Album.Type.EP -> AlbumGrouping.EPS
                            is Album.Type.Single -> AlbumGrouping.SINGLES
                            is Album.Type.Compilation -> AlbumGrouping.COMPILATIONS
                            is Album.Type.Soundtrack -> AlbumGrouping.SOUNDTRACKS
                            is Album.Type.Mix -> AlbumGrouping.MIXES
                            is Album.Type.Mixtape -> AlbumGrouping.MIXTAPES
                        }
                }
            }

        logD("Release groups for this artist: ${byReleaseGroup.keys}")

        for (entry in byReleaseGroup.entries.sortedBy { it.key }) {
            data.add(Header(entry.key.headerTitleRes))
            data.addAll(entry.value)
        }

        // Artists may not be linked to any songs, only include a header entry if we have any.
        if (artist.songs.isNotEmpty()) {
            logD("Songs present in this artist, adding header")
            data.add(SortHeader(R.string.lbl_songs))
            data.addAll(artistSort.songs(artist.songs))
        }

        _artistData.value = data.toList()
    }

    /**
     * Refresh [genreList] to reflect the given [Genre] and any [Sort] changes.
     * @param genre The [Genre] to create the list from.
     */
    private fun refreshGenreList(genre: Genre) {
        logD("Refreshing genre data")
        val data = mutableListOf<Item>(genre)
        // Genre is guaranteed to always have artists and songs.
        data.add(Header(R.string.lbl_artists))
        data.addAll(genre.artists)
        data.add(SortHeader(R.string.lbl_songs))
        data.addAll(genreSort.songs(genre.songs))
        _genreData.value = data
    }

    /**
     * A simpler mapping of [Album.Type] used for grouping and sorting songs.
     * @param headerTitleRes The title string resource to use for a header created
     * out of an instance of this enum.
     */
    private enum class AlbumGrouping(@StringRes val headerTitleRes: Int) {
        ALBUMS(R.string.lbl_albums),
        EPS(R.string.lbl_eps),
        SINGLES(R.string.lbl_singles),
        COMPILATIONS(R.string.lbl_compilations),
        SOUNDTRACKS(R.string.lbl_soundtracks),
        MIXES(R.string.lbl_mixes),
        MIXTAPES(R.string.lbl_mixtapes),
        LIVE(R.string.lbl_live_group),
        REMIXES(R.string.lbl_remix_group),
    }
}
