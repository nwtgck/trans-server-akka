#! /bin/sh

# Generate a keystore in current working directory
# from: https://www.glamenv-septzen.net/view/1075

KEYSTORE=trans.keystore
PASSWORD=changeit
ALIAS=testkey
DNAME="cn=trans, ou=trans, o=trans, l=Japan, st=Tokyo, c=JP"
CERT_PEM=testkey.pem


# $KEYSTORE found
if [ -e $KEYSTORE ]; then
    # rm with confirmation
    rm -i $KEYSTORE
fi

keytool -J-Dfile.encoding=UTF-8 \
  -keystore $KEYSTORE \
  -storepass $PASSWORD \
  -storetype jks \
  -genkeypair \
  -alias $ALIAS \
  -validity 3650 \
  -dname "$DNAME" \
  -keyalg RSA \
  -keysize 2048 \
  -keypass $PASSWORD
