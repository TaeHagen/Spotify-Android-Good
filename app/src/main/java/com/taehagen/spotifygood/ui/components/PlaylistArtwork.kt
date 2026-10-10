package com.taehagen.spotifygood.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.taehagen.spotifygood.data.MOSAIC_TILES
import com.taehagen.spotifygood.data.PlaylistMosaic
import com.taehagen.spotifygood.data.mosaicTiles
import com.taehagen.spotifygood.data.needsMosaic

// The art of a playlist without an image of its own (docs §9.8): the 2x2 mosaic of its first
// songs' covers, edge to edge, as Spotify draws it.

/**
 * The mosaic of the playlist [uri], learned when first shown (bounded, [com.taehagen.spotifygood.data.PlaylistMosaicStore]):
 * what memory held at once, then the store's answer, and again whenever it changes, goes stale or
 * the session comes online to learn it. Leaving the screen stops waiting for it.
 */
@Composable
fun rememberPlaylistMosaic(uri: String): PlaylistMosaic? {
    val store = rememberAppGraph().playlistMosaics
    val mosaic by produceState(store.peek(uri), uri, store) {
        value = store.mosaic(uri)
        store.changesOf(uri).collect { value = store.mosaic(uri) }
    }
    return mosaic
}

/**
 * Playlist art: its own image ([imageUrl]); without one, the mosaic of its first songs' covers
 * (4 distinct ones), the first song's cover alone (fewer), else the placeholder. [tilePx]: the
 * image size a tile asks for (half the art's). Nothing is learned for a playlist with an image.
 */
@Composable
fun PlaylistArtwork(
    uri: String,
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    tilePx: Int = 150,
    placeholderIcon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
) {
    if (!needsMosaic(uri, imageUrl)) {
        Artwork(imageUrl, contentDescription, modifier, shape, placeholderIcon)
        return
    }
    val covers = rememberPlaylistMosaic(uri)?.covers.orEmpty()
    if (covers.size >= MOSAIC_TILES) {
        MosaicArtwork(covers.take(MOSAIC_TILES).map { it.url(tilePx) }, contentDescription, modifier, shape)
    } else {
        Artwork(covers.firstOrNull()?.url(tilePx * 2), contentDescription, modifier, shape, placeholderIcon)
    }
}

/**
 * Four covers ([urls], reading order) as one square image: a 2x2 grid, each tile exactly a quarter
 * ([mosaicTiles]), no gap, padding, border or rounding between them; [shape] clips the whole.
 */
@Composable
fun MosaicArtwork(urls: List<String?>, contentDescription: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    val context = LocalPlatformContext.current
    val requests = remember(urls, context) {
        urls.map { url -> url?.takeIf { it.isNotBlank() }?.let { ImageRequest.Builder(context).data(imageData(it)).crossfade(true).build() } }
    }
    MosaicLayout(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        requests.forEach { request ->
            if (request != null) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop)
            } else {
                Box(Modifier)
            }
        }
    }
}

/** Lays its first [MOSAIC_TILES] children out as the tiles of a mosaic: exact quarters ([mosaicTiles]), edge to edge. */
@Composable
internal fun MosaicLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else width
        val tiles = mosaicTiles(width, height)
        val placeables = measurables.take(MOSAIC_TILES).mapIndexed { i, m -> m.measure(Constraints.fixed(tiles[i].width, tiles[i].height)) }
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.place(tiles[i].left, tiles[i].top) }
        }
    }
}
