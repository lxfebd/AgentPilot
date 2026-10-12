import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ConnectorSettingsStore } from '../../src/host/connector/settings.js'
import { readJson, writeJson } from '../../src/host/storage/files.js'
import { DEFAULT_CONNECTOR_SETTINGS } from '../../src/contracts/connector.js'

const aliyun = 'https://mirrors.aliyun.com/pypi/simple'

test('first Host initialization uses the official PyPI index for every locale without a Client', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'aa-plugin-mirror-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  for (const [languages, expectedPythonMirror] of [
    [['zh-CN'], 'https://registry.npmmirror.com/-/binary/python-build-standalone'],
    [['en-US', 'zh-Hans-CN'], 'https://registry.npmmirror.com/-/binary/python-build-standalone'],
    [['zh_TW'], 'https://registry.npmmirror.com/-/binary/python-build-standalone'],
    [['en-US'], ''],
  ] as const) {
    const config = { stateRoot: join(root, languages.join('-')), connectorSourceDir: root, uvPath: 'uv', apiBaseUrl: 'https://api.example.test' }
    const store = new ConnectorSettingsStore(config, async () => [...languages])
    await store.load()
    // PyPI 索引：任何语言默认都走官方源（阿里云等镜像可由用户在设置里自行选择）
    assert.equal(store.get().uvPypiIndexUrl, '')
    assert.equal(store.get().uvPythonInstallMirror, expectedPythonMirror)
    assert.deepEqual(await readJson(join(config.stateRoot, 'connector-settings.json')), store.get())
    const reopened = new ConnectorSettingsStore(config, async () => { assert.fail('Saved mirrors must not be re-detected') })
    await reopened.load()
    assert.equal(reopened.get().uvPypiIndexUrl, '')
    for (const choice of ['', 'https://pypi.tuna.tsinghua.edu.cn/simple']) {
      await store.save({ ...store.get(), uvPypiIndexUrl: choice })
      await reopened.load()
      assert.equal(reopened.get().uvPypiIndexUrl, choice)
    }
  }
})

test('legacy mirror migration preserves user choices and factory reset reapplies the system default', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'aa-plugin-mirror-legacy-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const path = join(root, 'connector-settings.json')
  const config = { stateRoot: root, connectorSourceDir: root, uvPath: 'uv', apiBaseUrl: 'https://api.example.test' }
  const store = new ConnectorSettingsStore(config, async () => ['zh-CN'])
  await writeJson(path, { uvPath: '', syncIntervalSeconds: 60, autoStart: false })
  await store.load()
  assert.deepEqual(store.get(), { ...DEFAULT_CONNECTOR_SETTINGS, syncIntervalSeconds: 60, uvPypiIndexUrl: '', uvPythonInstallMirror: 'https://registry.npmmirror.com/-/binary/python-build-standalone' })
  await writeJson(path, { ...DEFAULT_CONNECTOR_SETTINGS, autoStart: false })
  await store.load()
  assert.equal(store.get().uvPypiIndexUrl, '')
  await store.reset()
  assert.deepEqual(store.get(), { ...DEFAULT_CONNECTOR_SETTINGS, uvPypiIndexUrl: '', uvPythonInstallMirror: 'https://registry.npmmirror.com/-/binary/python-build-standalone' })
  assert.deepEqual(await readJson(path), store.get())
})
