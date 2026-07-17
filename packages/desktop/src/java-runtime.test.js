import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import {
  MINIMUM_JAVA_VERSION,
  parseJavaMajor,
  javaMissingMessage,
  javaVersionProblem,
} from "./java-runtime.js";

describe("parseJavaMajor", () => {
  it("reads the modern version scheme", () => {
    assert.equal(parseJavaMajor('openjdk version "21.0.11" 2026-04-21'), 21);
    assert.equal(parseJavaMajor('openjdk version "17.0.10" 2024-01-16'), 17);
    assert.equal(parseJavaMajor('java version "24" 2025-03-18'), 24);
  });

  it("reads the legacy 1.x scheme as its real major", () => {
    assert.equal(parseJavaMajor('java version "1.8.0_401"'), 8);
    assert.equal(parseJavaMajor('java version "1.7.0_80"'), 7);
  });

  it("returns null when there is no version string", () => {
    assert.equal(parseJavaMajor("command not found"), null);
    assert.equal(parseJavaMajor(""), null);
  });
});

describe("javaVersionProblem", () => {
  it("accepts the minimum and anything newer", () => {
    assert.equal(javaVersionProblem(MINIMUM_JAVA_VERSION), null);
    assert.equal(javaVersionProblem(MINIMUM_JAVA_VERSION + 3), null);
  });

  it("rejects anything older, naming the detected version", () => {
    const problem = javaVersionProblem(17);
    assert.ok(problem, "expected a problem message for Java 17");
    assert.match(problem, /version 17/);
    assert.match(problem, new RegExp(`Java ${MINIMUM_JAVA_VERSION}`));
  });

  it("does not block when the version could not be parsed", () => {
    assert.equal(javaVersionProblem(null), null);
  });
});

describe("javaMissingMessage", () => {
  it("tells the user to install Java 21", () => {
    const message = javaMissingMessage();
    assert.match(message, new RegExp(`Java ${MINIMUM_JAVA_VERSION}`));
    assert.match(message, /PATH/);
  });
});
