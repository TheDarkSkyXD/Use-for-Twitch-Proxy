/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.perflyst.twire.lowlatency;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

/** Default implementation for {@link HlsPlaylistParserFactory}. */
@UnstableApi public final class LLHlsPlaylistParserFactory implements HlsPlaylistParserFactory {

  @NonNull
  @Override
  public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
    return new LLHlsPlaylistParser();
  }

  @NonNull
  @Override
  public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
          @NonNull HlsMultivariantPlaylist multivariantPlaylist,
          @Nullable HlsMediaPlaylist previousMediaPlaylist) {
    return new LLHlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist);
  }
}
