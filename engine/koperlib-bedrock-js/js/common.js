// @minecraft/common. just the shared error types and ranges, nothing talks to java here
export class ArgumentOutOfBoundsError extends Error { constructor(m) { super(m); this.name = "ArgumentOutOfBoundsError"; } }
export class EngineError extends Error { constructor(m) { super(m); this.name = "EngineError"; } }
export class InvalidArgumentError extends Error { constructor(m) { super(m); this.name = "InvalidArgumentError"; } }
export class PropertyOutOfBoundsError extends Error { constructor(m) { super(m); this.name = "PropertyOutOfBoundsError"; } }
export class UnsupportedFunctionalityError extends Error { constructor(m) { super(m); this.name = "UnsupportedFunctionalityError"; } }
