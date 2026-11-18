# TV Launcher

Clean home screen for the EKO Android TV. Big tiles for the main apps, clock and date, All apps and Settings buttons.

GitHub builds the APK on every push. Grab `tv-launcher.apk` from the **latest** release.

## Change the home screen apps

Edit the `FAVOURITES` list at the top of `MainActivity.java`. Order in the list is the order on screen.

## Make it the home screen (ADB)

```
pm disable-user --user 0 com.google.android.tvlauncher
```

## Undo (back to the stock home screen)

```
pm enable com.google.android.tvlauncher
```
