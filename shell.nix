{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = [
    pkgs.leiningen
    pkgs.jdk
    pkgs.nodejs
  ];
}
