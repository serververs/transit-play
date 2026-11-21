// Generates the pre-built limbo packets the AntiBot uses for its in-game (gravity) check.
//
//   cd tools/limbo-gen && npm install && node gen.js
//
// Writes src/main/resources/limbo/<protocol>.bin for every protocol from 1.20.5 (766) up to the
// newest version minecraft-data knows. Re-run after bumping minecraft-data to add new versions.
//
// File format: varint count, then count x (varint keyLen, utf8 key, varint valLen, bytes).
// Packet values are "packet id + payload" (no length prefix, no compression).
'use strict'
const fs = require('fs')
const path = require('path')
const mc = require('minecraft-protocol')
const md = require('minecraft-data')

const OUT = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'limbo')
const MIN_PROTOCOL = 766
const SPAWN_Y = 200

function varint (v) {
  const out = []
  do {
    let b = v & 0x7f
    v >>>= 7
    if (v !== 0) b |= 0x80
    out.push(b)
  } while (v !== 0)
  return Buffer.from(out)
}
const str = s => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([varint(b.length), b]) }

function idOf (protocol, state, dir, name) {
  const m = protocol[state][dir].types.packet[1][0].type[1].mappings
  for (const [hex, n] of Object.entries(m)) if (n === name) return parseInt(hex, 16)
  return -1
}

function build (version) {
  const data = md(version)
  const proto = data.protocol
  const pv = data.version.version
  const ser = state => mc.createSerializer({ state, isServer: true, version })
  const pkt = (state, name, params) => ser(state).createPacketBuffer({ name, params })
  const entries = []
  const put = (k, v) => entries.push([k, Buffer.isBuffer(v) ? v : Buffer.from(v)])
  const putId = (k, state, dir, name) => {
    const id = idOf(proto, state, dir, name)
    if (id < 0) throw new Error(`${version}: no ${state}/${dir}/${name}`)
    put(k, varint(id))
  }

  put('version', str(version))

  // ---- login
  const success = pkt('login', 'success', { uuid: '00000000-0000-0000-0000-000000000000', username: 'A', properties: [], strictErrorHandling: false })
  // [id][16 uuid][varint 1]['A'][suffix...]
  const idLen = varint(idOf(proto, 'login', 'toClient', 'success')).length
  put('login.success.id', success.subarray(0, idLen))
  put('login.success.suffix', success.subarray(idLen + 16 + 2))
  putId('sb.login_ack', 'login', 'toServer', 'login_acknowledged')

  // ---- configuration
  put('cfg.known_packs', pkt('configuration', 'select_known_packs', { packs: [{ namespace: 'minecraft', id: 'core', version }] }))
  put('cfg.feature_flags', pkt('configuration', 'feature_flags', { features: ['minecraft:vanilla'] }))
  const codec = data.loginPacket.dimensionCodec
  const regs = Object.values(codec)
  put('cfg.registry.count', varint(regs.length))
  regs.forEach((r, i) => {
    put('cfg.registry.full.' + i, pkt('configuration', 'registry_data', r))
    put('cfg.registry.known.' + i, pkt('configuration', 'registry_data', { id: r.id, entries: r.entries.map(e => ({ key: e.key })) }))
  })
  put('cfg.finish', pkt('configuration', 'finish_configuration', {}))
  putId('cfg.disconnect.id', 'configuration', 'toClient', 'disconnect')
  putId('sb.cfg.known_packs', 'configuration', 'toServer', 'select_known_packs')
  putId('sb.cfg.finish', 'configuration', 'toServer', 'finish_configuration')

  // ---- play
  const login = JSON.parse(JSON.stringify(data.loginPacket))
  delete login.dimensionCodec
  login.viewDistance = 2
  login.simulationDistance = 2
  login.maxPlayers = 1
  login.enforcesSecureChat = false
  login.worldState.gamemode = 'adventure'
  login.worldState.previousGamemode = 255
  put('play.login', pkt('play', 'login', login))
  put('play.chunks_start', pkt('play', 'game_state_change', { reason: 'level_chunks_load_start', gameMode: 0 }))
  put('play.view_pos', pkt('play', 'update_view_position', { chunkX: 0, chunkZ: 0 }))

  const reg = require('prismarine-registry')(version)
  const Chunk = require('prismarine-chunk')(reg)
  const chunk = new Chunk({ minY: -64, worldHeight: 384 })
  const heightmapsArray = !!proto.play.toClient.types.packet_map_chunk[1].find(f => f.name === 'heightmaps' && Array.isArray(f.type) && f.type[0] === 'array')
  const chunkParams = {
    x: 0,
    z: 0,
    heightmaps: heightmapsArray ? [] : { type: 'compound', name: '', value: {} },
    chunkData: chunk.dump(),
    blockEntities: [],
    trustEdges: false,
    skyLightMask: [],
    blockLightMask: [],
    emptySkyLightMask: [],
    emptyBlockLightMask: [],
    skyLight: [],
    blockLight: []
  }
  put('play.batch_start', pkt('play', 'chunk_batch_start', {}))
  put('play.chunk', pkt('play', 'map_chunk', chunkParams))
  put('play.batch_end', pkt('play', 'chunk_batch_finished', { batchSize: 1 }))
  put('play.teleport', pkt('play', 'position', {
    teleportId: 1, x: 0.5, y: SPAWN_Y, z: 0.5, dx: 0, dy: 0, dz: 0, yaw: 0, pitch: 0, flags: {}
  }))
  put('play.spawn_y', Buffer.from(String(SPAWN_Y)))
  putId('play.disconnect.id', 'play', 'toClient', 'kick_disconnect')
  putId('play.keep_alive.id', 'play', 'toClient', 'keep_alive')

  putId('sb.play.teleport_confirm', 'play', 'toServer', 'teleport_confirm')
  putId('sb.play.position', 'play', 'toServer', 'position')
  putId('sb.play.position_look', 'play', 'toServer', 'position_look')
  putId('sb.play.keep_alive', 'play', 'toServer', 'keep_alive')

  const parts = [varint(entries.length)]
  for (const [k, v] of entries) parts.push(str(k), varint(v.length), v)
  return { pv, buf: Buffer.concat(parts) }
}

fs.mkdirSync(OUT, { recursive: true })
const byProtocol = {}
for (const v of md.supportedVersions.pc) {
  let d
  try { d = md(v) } catch { continue }
  const pv = d.version.version
  if (pv >= MIN_PROTOCOL && pv < 0x40000000 && d.loginPacket) byProtocol[pv] = v // last (newest) name wins
}
const done = []
for (const [pv, v] of Object.entries(byProtocol)) {
  try {
    const { buf } = build(v)
    fs.writeFileSync(path.join(OUT, pv + '.bin'), buf)
    done.push(`${pv}=${v}`)
  } catch (e) {
    console.error(`skip ${v} (${pv}): ${e.message}`)
  }
}
fs.writeFileSync(path.join(OUT, 'index.txt'), Object.keys(byProtocol).filter(pv => fs.existsSync(path.join(OUT, pv + '.bin'))).join('\n') + '\n')
console.log('generated', done.join(' '))
