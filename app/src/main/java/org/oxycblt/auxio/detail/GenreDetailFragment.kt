package org.oxycblt.auxio.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import org.oxycblt.auxio.R
import org.oxycblt.auxio.detail.adapters.GenreDetailAdapter
import org.oxycblt.auxio.logD
import org.oxycblt.auxio.music.BaseModel
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.playback.state.PlaybackMode
import org.oxycblt.auxio.ui.isLandscape
import org.oxycblt.auxio.ui.setupGenreSongActions

/**
 * The [DetailFragment] for a genre.
 * @author OxygenCobalt
 */
class GenreDetailFragment : DetailFragment() {
    private val args: GenreDetailFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // If DetailViewModel isn't already storing the genre, get it from MusicStore
        // using the ID given by the navigation arguments
        if (detailModel.currentGenre.value == null ||
            detailModel.currentGenre.value?.id != args.genreId
        ) {
            detailModel.updateGenre(
                MusicStore.getInstance().genres.find {
                    it.id == args.genreId
                }!!
            )
        }

        val detailAdapter = GenreDetailAdapter(
            detailModel, viewLifecycleOwner,
            doOnClick = {
                playbackModel.playSong(it, PlaybackMode.IN_GENRE)
            },
            doOnLongClick = { data, view ->
                PopupMenu(requireContext(), view).setupGenreSongActions(
                    requireContext(), data, playbackModel
                )
            }
        )

        // --- UI SETUP ---

        binding.lifecycleOwner = this

        setupToolbar(R.menu.menu_genre_actions) {
            when (it) {
                R.id.action_shuffle -> {
                    playbackModel.playGenre(
                        detailModel.currentGenre.value!!,
                        true
                    )

                    true
                }

                else -> false
            }
        }

        binding.detailRecycler.apply {
            adapter = detailAdapter
            setHasFixedSize(true)

            if (isLandscape(resources)) {
                layoutManager = GridLayoutManager(requireContext(), 2).also {
                    it.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (position == 0) 2 else 1
                        }
                    }
                }
            }
        }

        // --- VIEWMODEL SETUP ---

        detailModel.genreSortMode.observe(viewLifecycleOwner) { mode ->
            logD("Updating sort mode to $mode")

            val data = mutableListOf<BaseModel>(detailModel.currentGenre.value!!).also {
                it.addAll(mode.getSortedSongList(detailModel.currentGenre.value!!.songs))
            }

            detailAdapter.submitList(data)
        }

        logD("Fragment created.")

        return binding.root
    }
}
