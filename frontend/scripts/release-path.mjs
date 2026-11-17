import { posix } from 'node:path';

// Release descriptors contain URL paths, even when the build runs on Windows.
// Native path operations are reserved for resolving files on disk.
/** @param {unknown} value */
export function safeRelativePath(value) {
  return typeof value === 'string'
    && value.length > 0
    && !value.startsWith('/')
    && !value.includes('\\')
    && !value.includes(':')
    && posix.normalize(value) === value
    && value !== '.'
    && value !== '..'
    && !value.startsWith('../');
}
