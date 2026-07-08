plugins {
    id("bump-version-plugin")
    id("measure-build-plugin")
    id("convention.spotless")
    id("convention.detekt")
}

bumpVersionConfig {
    isEnabled = true
}
