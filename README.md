# IPTV Léger

Lecteur Xtream Codes minimal pour Android TV / Chromecast avec Google TV.
Films, séries, TV en direct, recherche, favoris, reprise de lecture.

Aucun identifiant n'est contenu dans ce projet : ils sont saisis dans l'appli
au premier lancement et restent sur l'appareil.

## Compiler l'APK avec GitHub (rien à installer)

1. Créer un compte sur github.com, puis un nouveau dépôt public nommé `iptv-leger`
   (sans README ni .gitignore).
2. Sur la page du dépôt : « uploading an existing file », puis glisser tout le
   contenu de ce dossier, y compris le dossier `.github`
   (sur Mac, Cmd+Maj+. pour l'afficher). Valider avec « Commit changes ».
3. Onglet « Actions » : la compilation démarre toute seule et dure 3 à 5 minutes.
   - Coche verte : l'APK est publié dans « Releases » (colonne de droite du dépôt).
   - Croix rouge : ouvrir l'exécution, copier les lignes d'erreur et les renvoyer
     pour correction.

## Installer sur le Chromecast

1. Installer l'appli « Downloader » depuis le Play Store du Chromecast.
2. Paramètres > Applications > Sécurité et restrictions > Sources inconnues :
   autoriser Downloader.
3. Dans Downloader, saisir l'adresse (remplacer COMPTE par le nom du compte GitHub) :

   `https://github.com/COMPTE/iptv-leger/releases/latest/download/iptv-leger.apk`

4. Installer, ouvrir, saisir l'adresse du serveur, l'identifiant et le mot de passe.

Les versions suivantes s'installent de la même façon, par-dessus l'ancienne,
sans perdre favoris ni reprises.

## Utilisation à la télécommande

- OK sur une affiche : lecture (reprise automatique là où on s'était arrêté).
- Appui long sur OK : ajouter ou retirer des favoris.
- Pendant un film : gauche = -10 s, droite = +30 s, OK = barre de contrôle
  (pause, sous-titres, roue dentée pour la piste audio).
- Séries : les épisodes d'une saison s'enchaînent automatiquement.
- Compte > « Actualiser le catalogue » : force le rechargement des listes.
