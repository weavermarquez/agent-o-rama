#!bin/sh

npm i
rm -rf resource/public
mkdir -p resource/public
cp -r resource/assets/* resource/public
lein with-profile +ui run -m shadow.cljs.devtools.cli --npm compile :frontend
