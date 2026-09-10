// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity ^0.8.24;

import {ERC721} from "@openzeppelin/contracts/token/ERC721/ERC721.sol";

/// @notice Public integrity anchors for encrypted MapSafe packages.
/// @dev Stores a safe package basename bound to its SHA-256; datasets never enter the chain.
contract MapSafeIntegrityRegistry is ERC721 {
    uint256 private constant HASH_LENGTH = 64;
    uint256 private constant MAX_FILENAME_LENGTH = 120;
    uint256 private _nextTokenId = 1;

    /// @notice Canonical MapSafe record associated with each token.
    mapping(uint256 tokenId => string record) public locations;

    event MapSafeRecordMinted(
        uint256 indexed tokenId,
        address indexed custodian,
        string record
    );

    constructor() ERC721("MapSafe Integrity Record", "MSIR") {}

    /// @notice Mint one immutable record for an encrypted package.
    /// @param record A safe package basename followed by `_` and 64 lowercase hex characters.
    function mintNFT(string calldata record) external returns (uint256 tokenId) {
        _requireCanonicalRecord(bytes(record));
        tokenId = _nextTokenId++;
        locations[tokenId] = record;
        _safeMint(msg.sender, tokenId);
        emit MapSafeRecordMinted(tokenId, msg.sender, record);
    }

    function _requireCanonicalRecord(bytes memory value) private pure {
        require(value.length > HASH_LENGTH + 1, "Missing MapSafe filename");
        uint256 separator = value.length - HASH_LENGTH - 1;
        require(separator <= MAX_FILENAME_LENGTH, "MapSafe filename too long");
        require(value[separator] == 0x5f, "Missing filename/hash separator");
        require(value[0] != 0x20 && value[0] != 0x2e, "Invalid filename boundary");
        require(
            value[separator - 1] != 0x20 && value[separator - 1] != 0x2e,
            "Invalid filename boundary"
        );
        for (uint256 i = 0; i < separator; ++i) {
            bytes1 character = value[i];
            bool upper = character >= 0x41 && character <= 0x5a;
            bool lower = character >= 0x61 && character <= 0x7a;
            bool digit = character >= 0x30 && character <= 0x39;
            bool punctuation = character == 0x20 || character == 0x2d ||
                character == 0x2e || character == 0x5f;
            require(upper || lower || digit || punctuation, "Invalid MapSafe filename");
        }
        for (uint256 i = separator + 1; i < value.length; ++i) {
            bytes1 character = value[i];
            bool digit = character >= 0x30 && character <= 0x39;
            bool lowerHex = character >= 0x61 && character <= 0x66;
            require(digit || lowerHex, "Invalid MapSafe SHA-256");
        }
    }
}
