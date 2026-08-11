package io.reliabilityai.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import io.reliabilityai.gateway.dataplane.streamguard.api.FramingType;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Streaming decode. The decoder receives payloads StreamGuard already deframed — these tests feed
 * it the same values StreamGuard's SSE decoder would emit, and assert only the canonical mapping.
 */
class OpenAiStreamingTest {

  private final OpenAiStreamingDecoder decoder =
      new OpenAiStreamingDecoder(new OpenAiResponseMapper());

  @Test
  void declaresSseFramingSoStreamGuardDeframesIt() {
    assertThat(decoder.framing()).isEqualTo(FramingType.SSE);
  }

  @Test
  void mapsAContentDeltaToATextChunk() {
    final Optional<StreamChunk> chunk =
        decoder.decode("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}");

    assertThat(chunk).containsInstanceOf(StreamChunk.Delta.class);
    assertThat(((StreamChunk.Delta) chunk.orElseThrow()).text()).isEqualTo("Hel");
  }

  @Test
  void ignoresRoleOnlyOpenerAndKeepAlives() {
    assertThat(decoder.decode("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}")).isEmpty();
    assertThat(decoder.decode("   ")).isEmpty();
  }

  @Test
  void mapsTheDoneSentinelToATerminalChunk() {
    final Optional<StreamChunk> chunk = decoder.decode(OpenAiStreamingDecoder.DONE_SENTINEL);

    final StreamChunk.Terminal terminal = (StreamChunk.Terminal) chunk.orElseThrow();
    assertThat(terminal.state()).isEqualTo(StreamState.TERMINAL_COMPLETE);
    assertThat(terminal.finishReason()).isEqualTo(FinishReason.STOP);
  }

  @Test
  void mapsFinishReasonToATerminalChunk() {
    final StreamChunk.Terminal terminal =
        (StreamChunk.Terminal)
            decoder
                .decode("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}")
                .orElseThrow();

    assertThat(terminal.finishReason()).isEqualTo(FinishReason.LENGTH);
    assertThat(terminal.state()).isEqualTo(StreamState.TERMINAL_COMPLETE);
  }

  @Test
  void anUnknownFinishReasonTerminatesAsFailedRatherThanCompleted() {
    final StreamChunk.Terminal terminal =
        (StreamChunk.Terminal)
            decoder
                .decode("{\"choices\":[{\"delta\":{},\"finish_reason\":\"who_knows\"}]}")
                .orElseThrow();

    // Reporting an unrecognised terminal as a clean completion would let a truncated answer
    // through.
    assertThat(terminal.finishReason()).isEqualTo(FinishReason.ERROR);
    assertThat(terminal.state()).isEqualTo(StreamState.TERMINAL_FAILED);
  }

  @Test
  void mapsAToolCallDelta() {
    final StreamChunk.ToolCallDelta chunk =
        (StreamChunk.ToolCallDelta)
            decoder
                .decode(
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}")
                .orElseThrow();

    assertThat(chunk.toolCall().name()).isEqualTo("lookup");
    assertThat(chunk.toolCall().callId()).isEqualTo("call_1");
  }

  @Test
  void mapsAUsageOnlyFrame() {
    final StreamChunk.Usage chunk =
        (StreamChunk.Usage)
            decoder
                .decode("{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}")
                .orElseThrow();

    assertThat(chunk.usage().prompt()).isEqualTo(7L);
    assertThat(chunk.usage().completion()).isEqualTo(2L);
  }

  @Test
  void malformedFrameThrowsRatherThanYieldingAPlausibleChunk() {
    assertThatThrownBy(() -> decoder.decode("{\"choices\":[{\"delta\":"))
        .isInstanceOf(Json.JsonException.class);
  }

  @Test
  void decodesAFullStreamInOrder() {
    final List<String> frames =
        List.of(
            "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            OpenAiStreamingDecoder.DONE_SENTINEL);

    final List<StreamChunk> chunks = new ArrayList<>();
    for (final String frame : frames) {
      decoder.decode(frame).ifPresent(chunks::add);
    }

    // OpenAI sends a finish_reason frame AND a [DONE] sentinel. The decoder is stateless by design
    // —
    // suppressing the second terminal would mean tracking stream state here, duplicating the
    // sequencing and dedup work StreamGuard already owns (Doc 18). Each terminal frame maps
    // independently; collapsing them is the consumer's concern.
    assertThat(chunks).hasSize(4);
    assertThat(((StreamChunk.Delta) chunks.get(0)).text()).isEqualTo("Hello");
    assertThat(((StreamChunk.Delta) chunks.get(1)).text()).isEqualTo(" world");
    assertThat(chunks.get(2)).isInstanceOf(StreamChunk.Terminal.class);
    assertThat(chunks.get(3)).isInstanceOf(StreamChunk.Terminal.class);
  }

  @Test
  void decodingIsDeterministic() {
    final String frame = "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}";
    final Optional<StreamChunk> first = decoder.decode(frame);

    for (int run = 0; run < 50; run++) {
      assertThat(decoder.decode(frame)).isEqualTo(first);
    }
  }
}
