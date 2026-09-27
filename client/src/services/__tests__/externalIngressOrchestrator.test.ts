// Delivery 1B Z3 — focused Vitest for the additive external-run surface of
// RecorderOrchestrator plus the microphone-path regression after the shared
// `finalizeRecording` extraction (Provider Contract §§B.5–B.6).
//
// All heavy collaborators are mocked; the real RecorderOrchestrator drives the
// frozen lifecycle. No network, no microphone, no Provider implementations.

import { beforeEach, describe, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => {
  const order: string[] = []
  const provider = {
    mode: 'server' as const,
    connect: vi.fn(async (_callbacks?: unknown) => {
      order.push('connect')
    }),
    isReady: vi.fn((): boolean => true),
    start: vi.fn((_opts?: unknown): boolean => {
      order.push('start')
      return true
    }),
    sendAudio: vi.fn((_buffer?: ArrayBuffer) => {
      order.push('sendAudio')
    }),
    stop: vi.fn((_opts?: unknown): boolean => {
      order.push('stop')
      return true
    }),
    cancel: vi.fn(() => {
      order.push('cancel')
    }),
    disconnect: vi.fn(),
  }
  return { order, provider }
})

vi.mock('../bridge', () => ({
  invoke: vi.fn(),
  listen: vi.fn(async () => () => {}),
  emit: vi.fn(),
  muteSystemOutput: vi.fn(async () => {}),
  restoreSystemOutput: vi.fn(async () => {}),
  onEscapeAction: vi.fn(),
  onPTTDown: vi.fn(),
  onPTTUp: vi.fn(),
  onPTTToggle: vi.fn(),
  onToggleHandsFree: vi.fn(),
  onPTTTimeoutWarning: vi.fn(),
  getClientRuntimeInfo: vi.fn(async () => null),
  getRecordingContext: vi.fn(),
  deleteAudioFile: vi.fn(async () => {}),
  pasteText: vi.fn(),
  getProbeResult: vi.fn(),
}))
vi.mock('../audio', () => ({
  startCapture: vi.fn(async () => ({ label: 'test-mic' })),
  stopCapture: vi.fn(async () => {}),
}))
vi.mock('../transcription', () => ({
  getProvider: () => fake.provider,
}))
vi.mock('../store', () => ({
  addHistory: vi.fn(async () => {}),
  deleteHistory: vi.fn(async () => {}),
  getActivePresetId: vi.fn(async () => 'intent'),
  getPromptPresets: vi.fn(async () => []),
  getSetting: vi.fn(async (key: string, fallback: unknown) => {
    const overrides: Record<string, unknown> = {
      historyEnabled: true,
      audioRetentionEnabled: false,
      streamingDisplayEnabled: false,
      aiEnabled: true,
    }
    return key in overrides ? overrides[key] : fallback
  }),
  setActivePresetId: vi.fn(async () => {}),
}))
vi.mock('../audioFileService', () => ({
  saveRecordingAudio: vi.fn(async () => 'unused-path'),
}))
vi.mock('../textInsertion', () => ({
  captureActiveInsertionTarget: vi.fn(() => ({ ok: true })),
  clearCapturedInsertionTarget: vi.fn(),
  startInsertionTargetTracking: vi.fn(),
  stopInsertionTargetTracking: vi.fn(),
}))
vi.mock('../textPostProcess', () => ({
  applyTextTransforms: vi.fn(async (text: string) => text),
}))
vi.mock('../watchIngress', () => ({
  initWatchIngress: vi.fn(),
  PHASE_B_BUDGET_MS: 3000,
}))
vi.mock('@/services/recorder/OverlayService', () => ({
  OverlayService: class {
    constructor(_elapsed?: unknown) {}
  },
}))
vi.mock('@/services/recorder/micSourceReminder', () => ({
  describeMicSource: vi.fn(() => ({ identity: 'test', mode: 'server', label: 'Test Mic' })),
  micSourceChanged: vi.fn(() => false),
}))
vi.mock('@/i18n', () => ({ t: (key: string) => key }))
vi.mock('@/lib/errorMessages', () => ({
  describeProviderError: (message: string) => ({ code: 'provider_failed', detail: message }),
}))
vi.mock('@/lib/asrModels', () => ({ isStreamingDisplayReady: () => false }))
vi.mock('../contextAware', () => ({
  CONTEXT_SELECTION_EDIT_PROMPT: '',
  CONTEXT_SELECTION_EDIT_PROMPT_SETTING_KEY: 'ctxSelectionEditPrompt',
  normalizeContextSelectionEditPrompt: (value: unknown) => String(value ?? ''),
  resolveContextAwareOutput: (input: { asrText: string; llmText: string }) => ({
    baseText: input.llmText || input.asrText,
    rawAsr: input.asrText,
    selectedEditWasApplied: false,
  }),
  usableTextContext: () => null,
  withContextAwareInstructions: (prompt: string) => prompt,
}))
vi.mock('@/services/personalization/defaults', () => ({
  createDefaultUserStats: () => ({ totalWords: 0, totalSessions: 0 }),
}))
vi.mock('@/services/personalization/promptRouter', () => ({
  resolvePromptRouting: vi.fn(() => ({
    preset: { id: 'preset', name: 'Preset' },
    appId: 'app',
    appName: 'App',
    matchedRule: undefined,
    summary: 'summary',
    systemPrompt: 'resolved Watch prompt',
  })),
}))
vi.mock('@/services/personalization/store', () => ({
  getAppPromptRules: vi.fn(async () => []),
  getUserStats: vi.fn(async () => ({ totalWords: 0, totalSessions: 0 })),
  recordSessionStats: vi.fn(async () => ({ totalWords: 0, totalSessions: 0 })),
}))
vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(async (cmd: string) => {
    if (cmd === 'watch_run_started') return true
    if (cmd === 'watch_run_aborted') return true
    if (cmd === 'get_mic_mute_state') return { matched: false, muted: false }
    return null
  }),
}))

import { invoke } from '@tauri-apps/api/core'
import { initWatchIngress } from '../watchIngress'
import * as bridge from '../bridge'
import * as audio from '../audio'
import * as textInsertion from '../textInsertion'
import { addHistory } from '../store'
import { RecorderOrchestrator } from '../recorder/RecorderOrchestrator'

const lastOf = <T,>(items: T[]): T => items[items.length - 1]

const REQ = '11111111-1111-4111-8111-111111111111'
const SAMPLE_COUNT = 16000
const PCM_BYTES = SAMPLE_COUNT * 2 // 32000 bytes; 32000 % 8192 = 7424 → short final chunk

function makePcm(sampleCount = SAMPLE_COUNT): ArrayBuffer {
  const pcm = new ArrayBuffer(sampleCount * 2)
  const u8 = new Uint8Array(pcm)
  for (let i = 0; i < u8.length; i++) u8[i] = (i * 31 + 7) & 0xff
  return pcm
}

function makeOverlayStub(): Record<string, unknown> {
  const cache: Record<string, unknown> = {}
  return new Proxy(cache, {
    get(target, prop) {
      if (prop === 'getBarCount') return () => 12
      if (typeof prop !== 'string') return undefined
      if (!(prop in target)) target[prop] = vi.fn(async () => {})
      return target[prop]
    },
  })
}

async function makeRecorder() {
  const recorder = new RecorderOrchestrator()
  ;(recorder as unknown as { overlayService: unknown }).overlayService = makeOverlayStub()
  await recorder.init()
  const handlerCalls = vi.mocked(initWatchIngress).mock.calls
  const handlers = handlerCalls[handlerCalls.length - 1][0]
  return { recorder, handlers }
}

function pttCallbacks() {
  const pttDown = lastOf(vi.mocked(bridge.onPTTDown).mock.calls)?.[0]
  const pttUp = lastOf(vi.mocked(bridge.onPTTUp).mock.calls)?.[0]
  return { pttDown, pttUp }
}

function providerCallbacks(): {
  onFinal: (r: { asrText: string; llmText: string; asrMs: number; llmMs: number; durationSec: number }) => void
  onError: (msg: string) => void
} {
  return lastOf(vi.mocked(fake.provider.connect).mock.calls)?.[0] as never
}

async function flushAsync(times = 8): Promise<void> {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setTimeout(resolve, 0))
}

beforeEach(() => {
  fake.order.length = 0
  vi.clearAllMocks()
  // Re-apply base behaviors cleared by clearAllMocks (order tracking included).
  fake.provider.connect.mockImplementation(async () => {
    fake.order.push('connect')
  })
  fake.provider.isReady.mockImplementation(() => true)
  fake.provider.start.mockImplementation(() => {
    fake.order.push('start')
    return true
  })
  fake.provider.sendAudio.mockImplementation(() => {
    fake.order.push('sendAudio')
  })
  fake.provider.stop.mockImplementation(() => {
    fake.order.push('stop')
    return true
  })
  fake.provider.cancel.mockImplementation(() => {
    fake.order.push('cancel')
  })
  vi.mocked(bridge.getRecordingContext).mockResolvedValue({
    appContext: { windowTitle: 'Notepad', processName: 'notepad.exe' },
    probe: { editable: true, hwnd: '123', probeId: 7, process: 'notepad.exe', detail: 'ok' },
  })
  vi.mocked(invoke).mockImplementation(async (cmd: string) => {
    if (cmd === 'watch_run_started') return true
    if (cmd === 'watch_run_aborted') return true
    if (cmd === 'get_mic_mute_state') return { matched: false, muted: false }
    return null
  })
})

describe('external reservation atomicity (Phase A)', () => {
  it('a PTT down cannot start a mic run while an external reservation is held', async () => {
    const { recorder, handlers } = await makeRecorder()
    const { pttDown } = pttCallbacks()

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    pttDown?.({})

    await flushAsync(3)
    expect(fake.order).toEqual([])
    expect(recorder.getState()).toBe('idle')
    expect(handlers.externalAdmissionBlocker()).toBe('already_reserved')
  })

  it('a PTT up cannot hijack the external run recording phase', async () => {
    const { recorder, handlers } = await makeRecorder()
    const { pttUp } = pttCallbacks()

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    // Simulate the external run occupying the recording state (beginExternalRun).
    ;(recorder as unknown as { state: string }).state = 'recording'
    pttUp?.({})

    await flushAsync(3)
    expect(audio.stopCapture).not.toHaveBeenCalled()
    expect(recorder.getState()).toBe('recording')
    // Cleanup for the next assertions.
    handlers.abortExternalRun(REQ, 'test_cleanup')
  })

  it('after the abort the orchestrator admits mic PTT again', async () => {
    const { recorder, handlers } = await makeRecorder()
    const { pttDown } = pttCallbacks()

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    handlers.abortExternalRun(REQ, 'test_cleanup')
    expect(handlers.externalAdmissionBlocker()).toBeNull()

    pttDown?.({})
    await flushAsync(3)
    expect(fake.order).toContain('connect')
  })
})

describe('external run lifecycle (§B.5)', () => {
  async function runToProcessing() {
    const { recorder, handlers } = await makeRecorder()
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })
    const ok = await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)
    expect(ok).toBe(true)
    return { recorder, handlers }
  }

  it('follows the frozen lifecycle order and sends no PCM before a successful start', async () => {
    const { recorder } = await runToProcessing()

    const firstStop = fake.order.indexOf('stop')
    const firstSend = fake.order.indexOf('sendAudio')
    const startIdx = fake.order.indexOf('start')
    expect(fake.order[0]).toBe('connect')
    expect(startIdx).toBe(1)
    expect(firstSend).toBeGreaterThan(startIdx)
    expect(firstStop).toBeGreaterThan(fake.order.lastIndexOf('sendAudio'))
    expect(recorder.getState()).toBe('processing')
  })

  it('feeds exact even-length slices with an exact short final chunk (Repair 2)', async () => {
    const { recorder } = await runToProcessing()

    // 32000 bytes: 3×8192 + 7424 → the final slice is 7424 bytes (3712 samples).
    const chunks = (recorder as unknown as { recordedChunks: ArrayBuffer[] }).recordedChunks
    expect(chunks.length).toBe(4)
    expect(chunks.slice(0, 3).map((c) => c.byteLength)).toEqual([8192, 8192, 8192])
    expect(chunks[3].byteLength).toBe(7424)

    // Byte-for-byte reconstruction of the admitted PCM.
    const joined = new Uint8Array(chunks.reduce((sum, c) => sum + c.byteLength, 0))
    let offset = 0
    for (const chunk of chunks) {
      joined.set(new Uint8Array(chunk), offset)
      offset += chunk.byteLength
    }
    const expected = new Uint8Array(makePcm())
    let byteForByte = joined.length === expected.length
    if (byteForByte) {
      for (let i = 0; i < joined.length; i++) {
        if (joined[i] !== expected[i]) {
          byteForByte = false
          break
        }
      }
    }
    expect(byteForByte).toBe(true)

    expect((recorder as unknown as { audioSentSamples: number }).audioSentSamples).toBe(SAMPLE_COUNT)
    expect((recorder as unknown as { wallTimeAtStopSec: number }).wallTimeAtStopSec).toBe(1)
  })

  it('rejects PCM whose length contradicts the admission sampleCount at entry', async () => {
    const { recorder, handlers } = await makeRecorder()
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })

    // Deliberately wrong admission metadata (as if the header lied): the entry
    // validation rejects before any connect/send happens.
    const ok = await handlers.beginExternalRun(REQ, makePcm(), 9999)
    expect(ok).toBe(false)

    expect(fake.order).toEqual([])
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'pcm_length_mismatch',
    })
    expect(recorder.getState()).toBe('idle')
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
  })

  it('aborts with sample_accounting_mismatch before provider.stop when the fed total disagrees', async () => {
    const { recorder, handlers } = await makeRecorder()
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })

    // Corrupt the bookkeeping mid-feed (simulates any future regression in the
    // chunk loop): the post-feed equality assertions must catch it before stop.
    fake.provider.sendAudio.mockImplementation(() => {
      fake.order.push('sendAudio')
      ;(recorder as unknown as { audioSentSamples: number }).audioSentSamples -= 1
    })

    const ok = await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)
    expect(ok).toBe(false)

    expect(fake.order).not.toContain('stop')
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'sample_accounting_mismatch',
    })
    expect(recorder.getState()).toBe('idle')
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
  })

  it('honors enabled AI cleanup and the resolved prompt for an external run', async () => {
    await runToProcessing()

    const startOpts = lastOf(vi.mocked(fake.provider.start).mock.calls)?.[0] as unknown as {
      disableAi?: boolean
      systemPrompt?: string
      runId: number
    }
    expect(startOpts.disableAi).toBe(false)
    expect(startOpts.systemPrompt).toBe('resolved Watch prompt')
    const stopOpts = lastOf(vi.mocked(fake.provider.stop).mock.calls)?.[0] as unknown as {
      disableAi?: boolean
    }
    expect(stopOpts.disableAi).toBeUndefined()
  })

  it('still honors the user turning AI cleanup off', async () => {
    const { recorder, handlers } = await makeRecorder()
    ;(recorder as unknown as { cachedAiEnabled: boolean }).cachedAiEnabled = false

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })
    expect(await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)).toBe(true)

    const startOpts = lastOf(vi.mocked(fake.provider.start).mock.calls)?.[0] as unknown as {
      disableAi?: boolean
      systemPrompt?: string
    }
    expect(startOpts.disableAi).toBe(true)
    expect(startOpts.systemPrompt).toBeUndefined()
  })

  it('captures the focus probe and native context exactly once per run', async () => {
    const { handlers } = await makeRecorder()
    const before = vi.mocked(textInsertion.captureActiveInsertionTarget).mock.calls.length

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(vi.mocked(textInsertion.captureActiveInsertionTarget).mock.calls.length).toBe(before + 1)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })
    expect(vi.mocked(textInsertion.captureActiveInsertionTarget).mock.calls.length).toBe(before + 1)
    expect(bridge.getRecordingContext).toHaveBeenCalledTimes(1)
  })

  it('Phase-B capture failure aborts both sides and frees the reservation', async () => {
    const { handlers } = await makeRecorder()
    vi.mocked(bridge.getRecordingContext).mockRejectedValue(new Error('probe down'))

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: false, reason: 'context_capture_failed' })

    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'context_capture_failed',
    })
    expect(handlers.hasExternalReservation(REQ)).toBe(false)
  })

  it('no provider session is created when start() returns false; both sides abort', async () => {
    const { handlers } = await makeRecorder()
    fake.provider.start.mockImplementationOnce(() => false)

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })
    expect(await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)).toBe(false)

    expect(fake.order).not.toContain('sendAudio')
    expect(invoke).not.toHaveBeenCalledWith('watch_run_started', { requestId: REQ })
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'provider_start_failed',
    })
  })

  it('a connect failure aborts both sides without touching PCM', async () => {
    const { handlers } = await makeRecorder()
    fake.provider.connect.mockImplementationOnce(async () => {
      throw new Error('socket refused')
    })

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })
    expect(await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)).toBe(false)

    expect(fake.order).not.toContain('sendAudio')
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'provider_connect_failed',
    })
  })

  it('reaches existing History exactly once and existing Paste exactly once via onFinal', async () => {
    const { recorder } = await runToProcessing()

    vi.mocked(bridge.pasteText).mockResolvedValue({ ok: true, strategy: 'native', detail: 'ok' })
    providerCallbacks().onFinal({
      asrText: 'asr text',
      llmText: 'final text',
      asrMs: 5,
      llmMs: 6,
      durationSec: 0.9,
    })
    await flushAsync()

    expect(addHistory).toHaveBeenCalledTimes(1)
    expect(vi.mocked(bridge.pasteText)).toHaveBeenCalledTimes(1)
    expect(recorder.getState()).toBe('idle')
    // finishRun released the admission gate on the success path.
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'run_finished',
    })
  })

  it('a provider error during processing still releases the gate exactly once', async () => {
    const { recorder } = await runToProcessing()

    providerCallbacks().onError('backend exploded')
    await flushAsync()

    expect(recorder.getState()).toBe('idle')
    expect(invoke).toHaveBeenCalledWith('watch_run_aborted', {
      requestId: REQ,
      reason: 'run_finished',
    })
    const abortCalls = vi.mocked(invoke).mock.calls.filter(
      ([cmd]) => cmd === 'watch_run_aborted',
    )
    expect(abortCalls.length).toBe(1)
  })

  it('abortExternalRun is request-conditional: stale IDs cannot clear a newer run', async () => {
    const { handlers } = await makeRecorder()

    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    handlers.abortExternalRun('22222222-2222-4222-8222-222222222222', 'stale')
    expect(handlers.hasExternalReservation(REQ)).toBe(true)

    handlers.abortExternalRun(REQ, 'cancel')
    expect(handlers.hasExternalReservation(REQ)).toBe(false)
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
  })
})

describe('Z3 Repair 3 — provider onError during an external run', () => {
  it('never calls mic stopCapture; releases the gate exactly once; no Paste; single History entry', async () => {
    const { recorder, handlers } = await makeRecorder()
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })

    // Fire the provider error synchronously inside the feed (second chunk), so
    // the run is in its `recording` phase with ≥0.5 s accounted samples.
    let sendCalls = 0
    fake.provider.sendAudio.mockImplementation((_buffer?: ArrayBuffer) => {
      fake.order.push('sendAudio')
      sendCalls++
      if (sendCalls === 2) providerCallbacks().onError('provider exploded mid-feed')
    })

    const ok = await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)
    await flushAsync(4)

    expect(ok).toBe(false)
    // Mic-only teardown must not have run.
    expect(audio.stopCapture).not.toHaveBeenCalled()
    // The feed aborted before any finalize/stop.
    expect(fake.order).not.toContain('stop')
    // Provider session cancelled; gate released exactly once via finishRun.
    expect(fake.order).toContain('cancel')
    const abortCalls = vi.mocked(invoke).mock.calls.filter(([cmd]) => cmd === 'watch_run_aborted')
    expect(abortCalls.length).toBe(1)
    expect(abortCalls[0]).toEqual(['watch_run_aborted', { requestId: REQ, reason: 'run_finished' }])
    // Standard single error-history entry; Paste never reached.
    expect(addHistory).toHaveBeenCalledTimes(1)
    expect(bridge.pasteText).not.toHaveBeenCalled()
    expect(recorder.getState()).toBe('idle')
    expect(handlers.hasExternalReservation(REQ)).toBe(false)
    // The orchestrator is reusable for the next run.
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
  })

  it('a stale second onError cannot duplicate cleanup or History', async () => {
    const { recorder, handlers } = await makeRecorder()
    expect(handlers.tryReserveExternalRun(REQ)).toBe(true)
    expect(await handlers.prepareExternalRun(REQ)).toEqual({ ok: true })

    let sendCalls = 0
    fake.provider.sendAudio.mockImplementation((_buffer?: ArrayBuffer) => {
      fake.order.push('sendAudio')
      sendCalls++
      if (sendCalls === 2) providerCallbacks().onError('first error')
    })

    const ok = await handlers.beginExternalRun(REQ, makePcm(), SAMPLE_COUNT)
    expect(ok).toBe(false)
    await flushAsync(4)

    // A second error from the already-finished generation must be dropped stale.
    providerCallbacks().onError('second error')
    await flushAsync(4)

    expect(addHistory).toHaveBeenCalledTimes(1)
    const abortCalls = vi.mocked(invoke).mock.calls.filter(([cmd]) => cmd === 'watch_run_aborted')
    expect(abortCalls.length).toBe(1)
    expect(recorder.getState()).toBe('idle')
    expect(audio.stopCapture).not.toHaveBeenCalled()
  })
})

describe('microphone path after the finalizeRecording extraction', () => {
  it('still stops, finalizes and reaches processing exactly as before', async () => {
    const { recorder } = await makeRecorder()
    const { pttDown, pttUp } = pttCallbacks()

    pttDown?.({})
    // startRecording awaits the parallel connect + capture setup.
    await flushAsync(4)
    expect(recorder.getState()).toBe('recording')

    // Feed one mic second through the captured capture callbacks.
    const startCall = lastOf(vi.mocked(audio.startCapture).mock.calls)
    const onData = startCall[1] as (buffer: ArrayBuffer) => void
    const onPcmFrame = startCall[3] as (frame: Int16Array) => void
    onData(new ArrayBuffer(32000))
    onPcmFrame(new Int16Array(16000))

    pttUp?.({})
    await flushAsync(4)

    expect(recorder.getState()).toBe('processing')
    expect(fake.order.filter((c) => c === 'stop')).toHaveLength(1)
    const stopOpts = lastOf(vi.mocked(fake.provider.stop).mock.calls)?.[0] as unknown as {
      pttHoldMs?: number
      audioStats?: unknown
      disableAi?: boolean
    }
    expect(typeof stopOpts.pttHoldMs).toBe('number')
    expect(stopOpts.audioStats).toBeTruthy()
    expect(stopOpts.disableAi).toBeUndefined()
  })
})
