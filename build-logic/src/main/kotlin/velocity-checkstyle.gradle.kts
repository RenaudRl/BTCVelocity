plugins {
    checkstyle
}

extensions.configure<CheckstyleExtension> {
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxErrors = 0
    maxWarnings = 0
    toolVersion = libs.checkstyle.get().version.toString()
    // Production : lowerCamelCase strict, deuxieme caractere minuscule ou chiffre.
    configProperties["methodNamePattern"] = "^[a-z][a-z0-9]\\w*$"
    configProperties["allowedAbbreviationLength"] = "0"
}

// Les noms de test sont des phrases (« aClaimAboutSomebodyOfflineIsRefused »). Le motif de
// production condamne tout nom commencant par un article, ce qui n'a rien a voir avec la qualite
// du code. La regle n'est pas levee, elle recoit un motif adapte : un nom de test doit toujours
// commencer par une minuscule et ne porter que des lettres et des chiffres — « Bad_Name » ou
// « TestFoo » restent refuses.
tasks.withType<Checkstyle>().configureEach {
    if (name.endsWith("Test")) {
        configProperties = configProperties.orEmpty() + mapOf(
            "methodNamePattern" to "^[a-z][a-zA-Z0-9]*$",
            "allowedAbbreviationLength" to "1",
        )
    }
}
