// Runs real frontend functions against the Go-generated synthetic fixture.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
if (!process.env.BRIDGE_SOURCE) throw new Error('Set BRIDGE_SOURCE to the audited checkout');
const moduleAt = (path: string) => pathToFileURL(resolve(process.env.BRIDGE_SOURCE!, path)).href;
const { connectProof } = await import(moduleAt('frontend/src/lib/gateway-credentials.ts'));
const { chunk, Reassembler, encodeWireFrame, decodeWireFrame } = await import(moduleAt('frontend/src/lib/transports/chunking.ts'));
const root = new URL('./fixtures/', import.meta.url);
const auth = JSON.parse(await readFile(new URL('gateway-auth.json', root), 'utf8'));
const proof = await connectProof(auth.rendezvous_key, auth.client_connect.relay_id, Buffer.from(auth.server_hello.nonce, 'base64url'));
assert.equal(Buffer.from(proof).toString('base64url'), auth.client_connect.proof);
const text = '{"type":"synthetic-framing-only"}';
const logical = encodeWireFrame(text);
const pieces = chunk(logical, 16); // Force multi-frame test independent of carrier ceiling.
const assembler = new Reassembler({onStall: () => { throw Error('unexpected stall'); }});
let decoded: Uint8Array | null = null;
for (const piece of pieces) decoded = assembler.push(piece);
assert.equal(decodeWireFrame(decoded!), text);
assembler.close();
await writeFile(new URL('chunk-vectors.json', root), JSON.stringify({logical_hex:Buffer.from(logical).toString('hex'),max_chunk_bytes:16,chunks_hex:pieces.map(p=>Buffer.from(p).toString('hex'))},null,2)+'\n');
console.log('PASS: TS matches Go HMAC; actual chunk encode/reassemble/decode roundtrip');
